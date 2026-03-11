"""
ClawPhones Billing Proxy — lightweight FastAPI backend.

Routes LLM API calls through OpenRouter, meters usage,
charges credits, and integrates Stripe Checkout for top-ups.

Client sends Anthropic Messages API format → we convert to OpenAI format
→ forward to OpenRouter → convert response back to Anthropic format.
"""

import hashlib
import hmac
import json
import os
import secrets
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

import aiosqlite
import httpx
import stripe
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Header, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse

load_dotenv()

# =============================================================================
# Config
# =============================================================================

OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "")
OPENROUTER_BASE_URL = os.getenv("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1")
STRIPE_SECRET_KEY = os.getenv("STRIPE_SECRET_KEY", "")
STRIPE_WEBHOOK_SECRET = os.getenv("STRIPE_WEBHOOK_SECRET", "")
STRIPE_SUCCESS_URL = os.getenv("STRIPE_SUCCESS_URL", "https://clawphones.ai/billing/success")
STRIPE_CANCEL_URL = os.getenv("STRIPE_CANCEL_URL", "https://clawphones.ai/billing/cancel")
ADMIN_KEY = os.getenv("ADMIN_KEY", "")
DB_PATH = os.getenv("DB_PATH", "./data/billing.sqlite3")
LISTEN_HOST = os.getenv("LISTEN_HOST", "0.0.0.0")
LISTEN_PORT = int(os.getenv("LISTEN_PORT", "8090"))
MOCK_MODE = os.getenv("MOCK_MODE", "0") == "1"

# Free tier daily token limit (no credits needed)
FREE_DAILY_TOKENS = int(os.getenv("FREE_DAILY_TOKENS", "10000"))

stripe.api_key = STRIPE_SECRET_KEY

# =============================================================================
# Credit pricing — this is where the markup lives
# =============================================================================
# 1 credit = $0.01 USD
# Users buy credits via Stripe, each API call costs credits based on token usage.
# Pricing via OpenRouter — we apply ~3-5x markup on top of OpenRouter cost.
#
# Model ID mapping: client sends Anthropic model IDs, we map to OpenRouter IDs.

# Anthropic model ID → OpenRouter model ID
MODEL_MAP = {
    # Claude models
    "claude-opus-4-6": "anthropic/claude-opus-4",
    "claude-sonnet-4-6": "anthropic/claude-sonnet-4",
    "claude-sonnet-4-5-20241022": "anthropic/claude-3.5-sonnet",
    "claude-haiku-4-5-20251001": "anthropic/claude-3.5-haiku",
    "claude-3-5-sonnet-20241022": "anthropic/claude-3.5-sonnet",
    "claude-3-5-haiku-20241022": "anthropic/claude-3.5-haiku",
    # GPT models (if client ever sends them)
    "gpt-4o": "openai/gpt-4o",
    "gpt-4o-mini": "openai/gpt-4o-mini",
    # Gemini
    "gemini-2.0-flash": "google/gemini-2.0-flash-001",
    # Llama
    "llama-3.1-70b": "meta-llama/llama-3.1-70b-instruct",
    # Free models (zero cost via OpenRouter)
    "free-nemotron": "nvidia/nemotron-3-nano-30b-a3b:free",
    "free-nemotron-9b": "nvidia/nemotron-nano-9b-v2:free",
    "free-trinity": "arcee-ai/trinity-large-preview:free",
}

# Models that cost nothing — skip credit checks entirely
FREE_MODELS = {
    "nvidia/nemotron-3-nano-30b-a3b:free",
    "nvidia/nemotron-nano-9b-v2:free",
    "arcee-ai/trinity-large-preview:free",
}

CREDIT_COST_PER_1K_INPUT = {
    # Claude
    "anthropic/claude-opus-4": 8,
    "anthropic/claude-sonnet-4": 2,
    "anthropic/claude-3.5-sonnet": 2,
    "anthropic/claude-3.5-haiku": 1,
    # GPT
    "openai/gpt-4o": 2,
    "openai/gpt-4o-mini": 1,
    # Gemini
    "google/gemini-2.0-flash-001": 1,
    # Llama
    "meta-llama/llama-3.1-70b-instruct": 1,
}

CREDIT_COST_PER_1K_OUTPUT = {
    # Claude
    "anthropic/claude-opus-4": 40,
    "anthropic/claude-sonnet-4": 8,
    "anthropic/claude-3.5-sonnet": 8,
    "anthropic/claude-3.5-haiku": 3,
    # GPT
    "openai/gpt-4o": 8,
    "openai/gpt-4o-mini": 2,
    # Gemini
    "google/gemini-2.0-flash-001": 2,
    # Llama
    "meta-llama/llama-3.1-70b-instruct": 2,
}

# Default for unknown models — conservative pricing
DEFAULT_COST_PER_1K_INPUT = 5
DEFAULT_COST_PER_1K_OUTPUT = 25


def _resolve_model(client_model: str) -> str:
    """Map client model ID to OpenRouter model ID."""
    # If already an OpenRouter ID (contains /), pass through
    if "/" in client_model:
        return client_model
    return MODEL_MAP.get(client_model, f"anthropic/{client_model}")

# Credit packages available for purchase
CREDIT_PACKAGES = [
    {"id": "starter", "name": "Starter", "credits": 500, "price_cents": 500, "bonus_pct": 0},
    {"id": "plus", "name": "Plus", "credits": 2200, "price_cents": 2000, "bonus_pct": 10},
    {"id": "pro", "name": "Pro", "credits": 6000, "price_cents": 5000, "bonus_pct": 20},
    {"id": "mega", "name": "Mega", "credits": 15000, "price_cents": 10000, "bonus_pct": 50},
]


def _calculate_credits(model: str, input_tokens: int, output_tokens: int) -> int:
    """Calculate credit cost for a given API call. Model should be OpenRouter ID."""
    input_cost = CREDIT_COST_PER_1K_INPUT.get(model, DEFAULT_COST_PER_1K_INPUT)
    output_cost = CREDIT_COST_PER_1K_OUTPUT.get(model, DEFAULT_COST_PER_1K_OUTPUT)
    cost = (input_tokens * input_cost + output_tokens * output_cost) / 1000
    return max(1, int(cost))  # Minimum 1 credit per call


# =============================================================================
# Database
# =============================================================================

async def _ensure_tables(db: aiosqlite.Connection):
    await db.executescript("""
        CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            device_id TEXT UNIQUE,
            api_token TEXT UNIQUE NOT NULL,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS wallet (
            user_id TEXT PRIMARY KEY REFERENCES users(id),
            available_credits INTEGER DEFAULT 0,
            lifetime_purchased INTEGER DEFAULT 0,
            lifetime_spent INTEGER DEFAULT 0
        );

        CREATE TABLE IF NOT EXISTS transactions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id),
            type TEXT NOT NULL,
            amount INTEGER NOT NULL,
            credits_before INTEGER NOT NULL,
            credits_after INTEGER NOT NULL,
            description TEXT,
            stripe_session_id TEXT,
            model TEXT,
            input_tokens INTEGER,
            output_tokens INTEGER,
            created_at INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_tx_user ON transactions(user_id, created_at DESC);
        CREATE INDEX IF NOT EXISTS idx_tx_stripe ON transactions(stripe_session_id);

        CREATE TABLE IF NOT EXISTS usage_daily (
            user_id TEXT NOT NULL,
            day TEXT NOT NULL,
            total_input_tokens INTEGER DEFAULT 0,
            total_output_tokens INTEGER DEFAULT 0,
            total_credits_spent INTEGER DEFAULT 0,
            request_count INTEGER DEFAULT 0,
            PRIMARY KEY (user_id, day)
        );
    """)
    await db.commit()


def _get_db():
    """Return an aiosqlite connection context manager."""
    conn = aiosqlite.connect(DB_PATH)
    return conn


# =============================================================================
# Auth helpers
# =============================================================================

def _gen_api_token() -> str:
    """Generate a ClawPhones API token: cp_xxxx"""
    return "cp_" + secrets.token_urlsafe(32)


async def _auth_user(authorization: str) -> dict:
    """Validate Bearer token, return user row."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="missing bearer token")
    token = authorization[7:].strip()
    if not token:
        raise HTTPException(status_code=401, detail="empty token")

    async with _get_db() as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT u.id, u.device_id, w.available_credits FROM users u "
            "LEFT JOIN wallet w ON w.user_id = u.id "
            "WHERE u.api_token = ?", (token,)
        ) as cur:
            row = await cur.fetchone()
    if not row:
        raise HTTPException(status_code=401, detail="invalid token")
    return dict(row)


# =============================================================================
# App lifecycle
# =============================================================================

@asynccontextmanager
async def lifespan(app: FastAPI):
    Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
    async with _get_db() as db:
        db.row_factory = aiosqlite.Row
        await _ensure_tables(db)
    yield

app = FastAPI(title="ClawPhones Billing Proxy", lifespan=lifespan)


# =============================================================================
# Health
# =============================================================================

@app.get("/health")
async def health():
    return {"status": "ok", "timestamp": int(time.time())}


# =============================================================================
# Auth endpoints
# =============================================================================

@app.post("/v1/auth/register")
async def register(request: Request):
    """Register a new device and get an API token + initial free credits."""
    body = await request.json()
    device_id = body.get("device_id", "").strip()
    if not device_id:
        raise HTTPException(status_code=400, detail="device_id required")

    api_token = _gen_api_token()
    user_id = str(uuid.uuid4())
    now = int(time.time())

    async with _get_db() as db:
        db.row_factory = aiosqlite.Row
        # Check if device already registered
        async with db.execute("SELECT id, api_token FROM users WHERE device_id = ?", (device_id,)) as cur:
            existing = await cur.fetchone()

        if existing:
            # Return existing token
            return {"user_id": existing["id"], "api_token": existing["api_token"], "existing": True}

        await db.execute(
            "INSERT INTO users (id, device_id, api_token, created_at) VALUES (?, ?, ?, ?)",
            (user_id, device_id, api_token, now),
        )
        await db.execute(
            "INSERT INTO wallet (user_id, available_credits, lifetime_purchased, lifetime_spent) "
            "VALUES (?, 100, 100, 0)",  # 100 free credits to start
            (user_id,),
        )
        await db.commit()

    return {"user_id": user_id, "api_token": api_token, "credits": 100, "existing": False}


# =============================================================================
# Format conversion: Anthropic Messages API ↔ OpenAI Chat Completions API
# =============================================================================

def _anthropic_to_openai(body: dict, or_model: str) -> dict:
    """Convert Anthropic Messages API request → OpenAI chat/completions format."""
    messages = []

    # System message (Anthropic puts it at top level)
    system = body.get("system")
    if system:
        if isinstance(system, str):
            messages.append({"role": "system", "content": system})
        elif isinstance(system, list):
            # Anthropic system can be list of content blocks
            text = "\n".join(b.get("text", "") for b in system if b.get("type") == "text")
            if text:
                messages.append({"role": "system", "content": text})

    # Convert messages
    for msg in body.get("messages", []):
        role = msg.get("role", "user")
        content = msg.get("content", "")

        if isinstance(content, str):
            messages.append({"role": role, "content": content})
        elif isinstance(content, list):
            # Anthropic content blocks → OpenAI content parts
            parts = []
            for block in content:
                if block.get("type") == "text":
                    parts.append({"type": "text", "text": block["text"]})
                elif block.get("type") == "image":
                    source = block.get("source", {})
                    if source.get("type") == "base64":
                        parts.append({
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:{source.get('media_type', 'image/png')};base64,{source.get('data', '')}"
                            },
                        })
                elif block.get("type") == "tool_use":
                    # Tool calls handled separately below
                    pass
                elif block.get("type") == "tool_result":
                    pass
            if parts:
                messages.append({"role": role, "content": parts if len(parts) > 1 else parts[0].get("text", parts)})

    result = {
        "model": or_model,
        "messages": messages,
        "max_tokens": body.get("max_tokens", 4096),
    }

    # Pass through optional params
    if "temperature" in body:
        result["temperature"] = body["temperature"]
    if "top_p" in body:
        result["top_p"] = body["top_p"]
    if body.get("stream"):
        result["stream"] = True

    return result


def _openai_to_anthropic(resp_data: dict, client_model: str) -> dict:
    """Convert OpenAI chat/completions response → Anthropic Messages API format."""
    choices = resp_data.get("choices", [])
    content_blocks = []

    if choices:
        choice = choices[0]
        msg = choice.get("message", {})
        text = msg.get("content", "")
        if text:
            content_blocks.append({"type": "text", "text": text})
        stop = choice.get("finish_reason", "end_turn")
        # Map OpenAI stop reasons to Anthropic
        stop_reason_map = {"stop": "end_turn", "length": "max_tokens", "content_filter": "end_turn"}
        stop_reason = stop_reason_map.get(stop, "end_turn")
    else:
        stop_reason = "end_turn"

    usage = resp_data.get("usage", {})

    return {
        "id": resp_data.get("id", f"msg_{secrets.token_hex(12)}"),
        "type": "message",
        "role": "assistant",
        "content": content_blocks,
        "model": client_model,  # Return the model ID the client sent
        "stop_reason": stop_reason,
        "usage": {
            "input_tokens": usage.get("prompt_tokens", 0),
            "output_tokens": usage.get("completion_tokens", 0),
        },
    }


def _openai_sse_to_anthropic_sse(line: str, client_model: str, msg_id: str) -> str:
    """Convert a single OpenAI SSE data line → Anthropic SSE format."""
    if not line.startswith("data: "):
        return line
    payload = line[6:].strip()
    if payload == "[DONE]":
        return "event: message_stop\ndata: {\"type\": \"message_stop\"}\n"

    try:
        chunk = json.loads(payload)
    except json.JSONDecodeError:
        return line

    choices = chunk.get("choices", [])
    if not choices:
        return ""

    delta = choices[0].get("delta", {})
    finish = choices[0].get("finish_reason")

    # Content delta
    text = delta.get("content", "")
    if text:
        event = {
            "type": "content_block_delta",
            "index": 0,
            "delta": {"type": "text_delta", "text": text},
        }
        return f"event: content_block_delta\ndata: {json.dumps(event)}\n"

    # Finish
    if finish:
        usage = chunk.get("usage", {})
        stop_map = {"stop": "end_turn", "length": "max_tokens"}
        event = {
            "type": "message_delta",
            "delta": {"stop_reason": stop_map.get(finish, "end_turn")},
            "usage": {"output_tokens": usage.get("completion_tokens", 0)},
        }
        return f"event: content_block_stop\ndata: {{\"type\": \"content_block_stop\", \"index\": 0}}\nevent: message_delta\ndata: {json.dumps(event)}\n"

    return ""


# =============================================================================
# LLM API Proxy (via OpenRouter)
# =============================================================================

@app.post("/v1/messages")
async def proxy_messages(
    request: Request,
    authorization: str = Header(None),
):
    """Proxy LLM Messages API via OpenRouter — check credits, forward request, deduct credits."""
    user = await _auth_user(authorization)
    user_id = user["id"]
    available = user["available_credits"] or 0

    body = await request.json()
    client_model = body.get("model", "claude-sonnet-4-6")
    or_model = _resolve_model(client_model)
    is_free_model = or_model in FREE_MODELS

    # Estimate cost before calling (rough: assume 500 output tokens)
    est_input = sum(
        len(str(m.get("content", ""))) // 4
        for m in body.get("messages", [])
    )
    est_cost = 0 if is_free_model else _calculate_credits(or_model, est_input, 500)

    # Free models: skip all credit checks
    in_free_tier = False
    if is_free_model:
        in_free_tier = True  # Treat as free tier for billing metadata
    else:
        # Check daily free tier for paid models
        today = time.strftime("%Y-%m-%d")
        async with _get_db() as db:
            db.row_factory = aiosqlite.Row
            async with db.execute(
                "SELECT total_input_tokens + total_output_tokens as total "
                "FROM usage_daily WHERE user_id = ? AND day = ?",
                (user_id, today),
            ) as cur:
                daily = await cur.fetchone()
        daily_used = daily["total"] if daily else 0

        # If within free tier, allow without credits
        in_free_tier = daily_used < FREE_DAILY_TOKENS and available <= 0

        if not in_free_tier and available < est_cost:
            raise HTTPException(
                status_code=402,
                detail={
                    "error": "insufficient_credits",
                    "available": available,
                    "estimated_cost": est_cost,
                    "message": "Top up credits to continue. Visit /v1/billing/packages for options.",
                },
            )

    # Forward to OpenRouter
    if MOCK_MODE:
        # Mock response for testing (return Anthropic format directly)
        mock_reply = f"[MOCK] Echo: {body.get('messages', [{}])[-1].get('content', '')}"
        result = {
            "id": f"msg_{secrets.token_hex(12)}",
            "type": "message",
            "role": "assistant",
            "content": [{"type": "text", "text": mock_reply}],
            "model": client_model,
            "stop_reason": "end_turn",
            "usage": {"input_tokens": est_input, "output_tokens": len(mock_reply) // 4},
        }
    else:
        if not OPENROUTER_API_KEY:
            raise HTTPException(status_code=503, detail="API key not configured")

        # Convert Anthropic format → OpenAI format for OpenRouter
        openai_body = _anthropic_to_openai(body, or_model)

        async with httpx.AsyncClient(timeout=120.0) as client:
            headers = {
                "Authorization": f"Bearer {OPENROUTER_API_KEY}",
                "Content-Type": "application/json",
                "HTTP-Referer": "https://clawphones.ai",
                "X-Title": "ClawPhones",
            }

            resp = await client.post(
                f"{OPENROUTER_BASE_URL}/chat/completions",
                headers=headers,
                json=openai_body,
            )

            if resp.status_code != 200:
                # Try to convert OpenRouter error to Anthropic-style error
                try:
                    err = resp.json()
                    detail = err.get("error", {}).get("message", resp.text)
                except Exception:
                    detail = resp.text
                return Response(
                    content=json.dumps({"type": "error", "error": {"type": "api_error", "message": detail}}),
                    status_code=resp.status_code,
                    media_type="application/json",
                )

            # Convert OpenAI response → Anthropic format
            result = _openai_to_anthropic(resp.json(), client_model)

    # Extract actual usage
    usage = result.get("usage", {})
    input_tokens = usage.get("input_tokens", est_input)
    output_tokens = usage.get("output_tokens", 0)
    actual_cost = 0 if is_free_model else _calculate_credits(or_model, input_tokens, output_tokens)

    # Deduct credits (skip if free model or in free tier)
    today = time.strftime("%Y-%m-%d")
    async with _get_db() as db:
        db.row_factory = aiosqlite.Row
        if not in_free_tier and not is_free_model:
            async with db.execute(
                "SELECT available_credits FROM wallet WHERE user_id = ?", (user_id,)
            ) as cur:
                wallet = await cur.fetchone()

            credits_before = wallet["available_credits"] if wallet else 0
            credits_after = max(0, credits_before - actual_cost)

            await db.execute(
                "UPDATE wallet SET available_credits = ?, lifetime_spent = lifetime_spent + ? "
                "WHERE user_id = ?",
                (credits_after, actual_cost, user_id),
            )

            # Record transaction
            await db.execute(
                "INSERT INTO transactions "
                "(id, user_id, type, amount, credits_before, credits_after, description, model, input_tokens, output_tokens, created_at) "
                "VALUES (?, ?, 'usage', ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    str(uuid.uuid4()), user_id, -actual_cost,
                    credits_before, credits_after,
                    f"{or_model} ({input_tokens}in/{output_tokens}out)",
                    or_model, input_tokens, output_tokens, int(time.time()),
                ),
            )

        # Update daily usage
        await db.execute(
            "INSERT INTO usage_daily (user_id, day, total_input_tokens, total_output_tokens, total_credits_spent, request_count) "
            "VALUES (?, ?, ?, ?, ?, 1) "
            "ON CONFLICT(user_id, day) DO UPDATE SET "
            "total_input_tokens = total_input_tokens + ?, "
            "total_output_tokens = total_output_tokens + ?, "
            "total_credits_spent = total_credits_spent + ?, "
            "request_count = request_count + 1",
            (user_id, today, input_tokens, output_tokens, actual_cost,
             input_tokens, output_tokens, actual_cost),
        )
        await db.commit()

    # Inject billing metadata into response
    if is_free_model or in_free_tier:
        remaining = available  # No credits consumed
    else:
        remaining = credits_after  # noqa: F821 — defined in deduction block above
    result["_billing"] = {
        "credits_used": actual_cost,
        "credits_remaining": remaining,
        "free_tier": in_free_tier or is_free_model,
    }

    return result


# =============================================================================
# Streaming proxy — SSE passthrough with usage tracking
# =============================================================================

@app.post("/v1/messages/stream")
async def proxy_messages_stream(
    request: Request,
    authorization: str = Header(None),
):
    """Proxy LLM streaming via OpenRouter — convert SSE format on the fly."""
    user = await _auth_user(authorization)
    user_id = user["id"]
    available = user["available_credits"] or 0

    body = await request.json()
    client_model = body.get("model", "claude-sonnet-4-6")
    or_model = _resolve_model(client_model)
    is_free_model = or_model in FREE_MODELS

    # Free models: skip credit checks entirely
    if not is_free_model and available <= 0:
        today = time.strftime("%Y-%m-%d")
        async with _get_db() as db:
            db.row_factory = aiosqlite.Row
            async with db.execute(
                "SELECT total_input_tokens + total_output_tokens as total "
                "FROM usage_daily WHERE user_id = ? AND day = ?",
                (user_id, today),
            ) as cur:
                daily = await cur.fetchone()
        daily_used = daily["total"] if daily else 0
        if daily_used >= FREE_DAILY_TOKENS:
            raise HTTPException(status_code=402, detail="insufficient credits")

    if MOCK_MODE:
        raise HTTPException(status_code=501, detail="streaming not available in mock mode")

    if not OPENROUTER_API_KEY:
        raise HTTPException(status_code=503, detail="API key not configured")

    # Convert Anthropic request → OpenAI format with stream=True
    openai_body = _anthropic_to_openai(body, or_model)
    openai_body["stream"] = True
    openai_body["stream_options"] = {"include_usage": True}
    msg_id = f"msg_{secrets.token_hex(12)}"

    async def stream_and_track():
        input_tokens = 0
        output_tokens = 0

        # Emit Anthropic-style message_start event
        start_event = {
            "type": "message_start",
            "message": {
                "id": msg_id, "type": "message", "role": "assistant",
                "content": [], "model": client_model,
                "usage": {"input_tokens": 0, "output_tokens": 0},
            },
        }
        yield f"event: message_start\ndata: {json.dumps(start_event)}\n\n"
        yield f"event: content_block_start\ndata: {{\"type\": \"content_block_start\", \"index\": 0, \"content_block\": {{\"type\": \"text\", \"text\": \"\"}}}}\n\n"

        async with httpx.AsyncClient(timeout=300.0) as client:
            headers = {
                "Authorization": f"Bearer {OPENROUTER_API_KEY}",
                "Content-Type": "application/json",
                "HTTP-Referer": "https://clawphones.ai",
                "X-Title": "ClawPhones",
            }

            async with client.stream(
                "POST",
                f"{OPENROUTER_BASE_URL}/chat/completions",
                headers=headers,
                json=openai_body,
                timeout=300.0,
            ) as resp:
                if resp.status_code != 200:
                    error_body = await resp.aread()
                    yield f"data: {error_body.decode()}\n\n"
                    return

                async for line in resp.aiter_lines():
                    if not line.strip():
                        continue

                    # Convert OpenAI SSE → Anthropic SSE
                    converted = _openai_sse_to_anthropic_sse(line, client_model, msg_id)
                    if converted:
                        yield f"{converted}\n"

                    # Track usage from OpenAI chunks
                    if line.startswith("data: ") and line[6:].strip() != "[DONE]":
                        try:
                            chunk = json.loads(line[6:])
                            usage = chunk.get("usage", {})
                            if usage.get("prompt_tokens"):
                                input_tokens = usage["prompt_tokens"]
                            if usage.get("completion_tokens"):
                                output_tokens = usage["completion_tokens"]
                        except (json.JSONDecodeError, AttributeError):
                            pass

        # After stream completes — deduct credits (skip for free models)
        if input_tokens > 0 or output_tokens > 0:
            actual_cost = 0 if is_free_model else _calculate_credits(or_model, input_tokens, output_tokens)
            today = time.strftime("%Y-%m-%d")
            try:
                async with _get_db() as db:
                    db.row_factory = aiosqlite.Row

                    if not is_free_model:
                        async with db.execute(
                            "SELECT available_credits FROM wallet WHERE user_id = ?", (user_id,)
                        ) as cur:
                            wallet = await cur.fetchone()

                        credits_before = wallet["available_credits"] if wallet else 0
                        credits_after = max(0, credits_before - actual_cost)

                        if credits_before > 0:
                            await db.execute(
                                "UPDATE wallet SET available_credits = ?, lifetime_spent = lifetime_spent + ? "
                                "WHERE user_id = ?",
                                (credits_after, actual_cost, user_id),
                            )
                            await db.execute(
                                "INSERT INTO transactions "
                                "(id, user_id, type, amount, credits_before, credits_after, description, model, input_tokens, output_tokens, created_at) "
                                "VALUES (?, ?, 'usage', ?, ?, ?, ?, ?, ?, ?, ?)",
                                (
                                    str(uuid.uuid4()), user_id, -actual_cost,
                                    credits_before, credits_after,
                                    f"{or_model} stream ({input_tokens}in/{output_tokens}out)",
                                    or_model, input_tokens, output_tokens, int(time.time()),
                                ),
                            )

                    await db.execute(
                        "INSERT INTO usage_daily (user_id, day, total_input_tokens, total_output_tokens, total_credits_spent, request_count) "
                        "VALUES (?, ?, ?, ?, ?, 1) "
                        "ON CONFLICT(user_id, day) DO UPDATE SET "
                        "total_input_tokens = total_input_tokens + ?, "
                        "total_output_tokens = total_output_tokens + ?, "
                        "total_credits_spent = total_credits_spent + ?, "
                        "request_count = request_count + 1",
                        (user_id, today, input_tokens, output_tokens, actual_cost,
                         input_tokens, output_tokens, actual_cost),
                    )
                    await db.commit()
            except Exception:
                pass  # Non-fatal — don't break the stream

    return StreamingResponse(
        stream_and_track(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


# =============================================================================
# Billing endpoints
# =============================================================================

@app.get("/v1/billing/packages")
async def get_packages():
    """List available credit packages."""
    return {"packages": CREDIT_PACKAGES}


@app.get("/v1/billing/wallet")
async def get_wallet(authorization: str = Header(None)):
    """Get current wallet balance."""
    user = await _auth_user(authorization)
    async with _get_db() as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT * FROM wallet WHERE user_id = ?", (user["id"],)
        ) as cur:
            wallet = await cur.fetchone()

    if not wallet:
        return {"available_credits": 0, "lifetime_purchased": 0, "lifetime_spent": 0}

    return {
        "available_credits": wallet["available_credits"],
        "lifetime_purchased": wallet["lifetime_purchased"],
        "lifetime_spent": wallet["lifetime_spent"],
    }


@app.post("/v1/billing/checkout")
async def create_checkout(request: Request, authorization: str = Header(None)):
    """Create a Stripe Checkout session to buy credits."""
    user = await _auth_user(authorization)
    body = await request.json()
    package_id = body.get("package_id", "").strip()

    pkg = next((p for p in CREDIT_PACKAGES if p["id"] == package_id), None)
    if not pkg:
        raise HTTPException(status_code=400, detail="invalid package_id")

    if not STRIPE_SECRET_KEY:
        raise HTTPException(status_code=503, detail="Stripe not configured")

    session = stripe.checkout.Session.create(
        payment_method_types=["card"],
        line_items=[{
            "price_data": {
                "currency": "usd",
                "unit_amount": pkg["price_cents"],
                "product_data": {
                    "name": f"ClawPhones Credits — {pkg['name']}",
                    "description": f"{pkg['credits']} credits",
                },
            },
            "quantity": 1,
        }],
        mode="payment",
        success_url=STRIPE_SUCCESS_URL + "?session_id={CHECKOUT_SESSION_ID}",
        cancel_url=STRIPE_CANCEL_URL,
        metadata={
            "user_id": user["id"],
            "package_id": package_id,
            "credits": str(pkg["credits"]),
        },
        client_reference_id=user["id"],
    )

    return {"checkout_url": session.url, "session_id": session.id}


@app.post("/v1/billing/webhook")
async def stripe_webhook(request: Request):
    """Handle Stripe webhook events. Idempotent — safe to receive duplicates."""
    payload = await request.body()
    sig = request.headers.get("stripe-signature", "")

    if not STRIPE_WEBHOOK_SECRET:
        raise HTTPException(status_code=503, detail="webhook secret not configured")

    try:
        event = stripe.Webhook.construct_event(payload, sig, STRIPE_WEBHOOK_SECRET)
    except stripe.error.SignatureVerificationError:
        raise HTTPException(status_code=400, detail="invalid signature")
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))

    if event["type"] == "checkout.session.completed":
        session = event["data"]["object"]
        session_id = session["id"]
        meta = session.get("metadata", {})
        user_id = meta.get("user_id")
        credits = int(meta.get("credits", 0))

        if not user_id or credits <= 0:
            return {"status": "ignored", "reason": "missing metadata"}

        async with _get_db() as db:
            db.row_factory = aiosqlite.Row
            # Idempotent check — don't double-credit
            async with db.execute(
                "SELECT id FROM transactions WHERE stripe_session_id = ?", (session_id,)
            ) as cur:
                existing = await cur.fetchone()

            if existing:
                return {"status": "already_processed"}

            # Credit the wallet
            await db.execute(
                "INSERT INTO wallet (user_id, available_credits, lifetime_purchased, lifetime_spent) "
                "VALUES (?, ?, ?, 0) "
                "ON CONFLICT(user_id) DO UPDATE SET "
                "available_credits = available_credits + ?, "
                "lifetime_purchased = lifetime_purchased + ?",
                (user_id, credits, credits, credits, credits),
            )

            # Get updated balance for transaction record
            async with db.execute(
                "SELECT available_credits FROM wallet WHERE user_id = ?", (user_id,)
            ) as cur:
                wallet = await cur.fetchone()

            credits_after = wallet["available_credits"] if wallet else credits

            await db.execute(
                "INSERT INTO transactions "
                "(id, user_id, type, amount, credits_before, credits_after, description, stripe_session_id, created_at) "
                "VALUES (?, ?, 'purchase', ?, ?, ?, ?, ?, ?)",
                (
                    str(uuid.uuid4()), user_id, credits,
                    credits_after - credits, credits_after,
                    f"Purchased {credits} credits",
                    session_id, int(time.time()),
                ),
            )
            await db.commit()

        return {"status": "credited", "credits": credits}

    return {"status": "ignored", "event_type": event["type"]}


@app.get("/v1/billing/history")
async def get_billing_history(
    authorization: str = Header(None),
    limit: int = 50,
    offset: int = 0,
):
    """Get transaction history for the authenticated user."""
    user = await _auth_user(authorization)
    limit = min(limit, 200)

    async with _get_db() as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT id, type, amount, credits_before, credits_after, description, "
            "model, input_tokens, output_tokens, created_at "
            "FROM transactions WHERE user_id = ? "
            "ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (user["id"], limit, offset),
        ) as cur:
            rows = await cur.fetchall()

    return {"transactions": [dict(r) for r in rows]}


@app.get("/v1/billing/usage")
async def get_usage(
    authorization: str = Header(None),
    days: int = 7,
):
    """Get daily usage stats."""
    user = await _auth_user(authorization)
    days = min(days, 90)

    async with _get_db() as db:
        db.row_factory = aiosqlite.Row
        async with db.execute(
            "SELECT day, total_input_tokens, total_output_tokens, "
            "total_credits_spent, request_count "
            "FROM usage_daily WHERE user_id = ? "
            "ORDER BY day DESC LIMIT ?",
            (user["id"], days),
        ) as cur:
            rows = await cur.fetchall()

    return {"usage": [dict(r) for r in rows]}


# =============================================================================
# Admin endpoints (optional, for manual credit grants etc.)
# =============================================================================

@app.post("/admin/credits/grant")
async def admin_grant_credits(request: Request):
    """Grant credits to a user (admin only)."""
    admin_key = request.headers.get("x-admin-key", "")
    if not ADMIN_KEY or not hmac.compare_digest(admin_key, ADMIN_KEY):
        raise HTTPException(status_code=403, detail="forbidden")

    body = await request.json()
    user_id = body.get("user_id", "").strip()
    credits = int(body.get("credits", 0))

    if not user_id or credits <= 0:
        raise HTTPException(status_code=400, detail="user_id and credits required")

    async with _get_db() as db:
        db.row_factory = aiosqlite.Row
        await db.execute(
            "UPDATE wallet SET available_credits = available_credits + ?, "
            "lifetime_purchased = lifetime_purchased + ? WHERE user_id = ?",
            (credits, credits, user_id),
        )
        async with db.execute(
            "SELECT available_credits FROM wallet WHERE user_id = ?", (user_id,)
        ) as cur:
            wallet = await cur.fetchone()

        if not wallet:
            raise HTTPException(status_code=404, detail="user not found")

        await db.execute(
            "INSERT INTO transactions "
            "(id, user_id, type, amount, credits_before, credits_after, description, created_at) "
            "VALUES (?, ?, 'grant', ?, ?, ?, 'Admin grant', ?)",
            (
                str(uuid.uuid4()), user_id, credits,
                wallet["available_credits"] - credits, wallet["available_credits"],
                int(time.time()),
            ),
        )
        await db.commit()

    return {"status": "ok", "credits": wallet["available_credits"]}


# =============================================================================
# Models endpoint — available models with free/paid classification
# =============================================================================

@app.get("/v1/models")
async def list_models():
    """List available models grouped by tier (free / paid)."""
    return {
        "models": [
            # Free tier — zero cost, always available
            {"id": "free-nemotron", "name": "Nemotron Nano 30B", "provider": "NVIDIA", "tier": "free", "context": 131072, "description": "Fast, free, agentic"},
            {"id": "free-nemotron-9b", "name": "Nemotron Nano 9B", "provider": "NVIDIA", "tier": "free", "context": 128000, "description": "Free, fast, efficient"},
            {"id": "free-trinity", "name": "Trinity Large", "provider": "Arcee", "tier": "free", "context": 131072, "description": "Free, smart, long context"},
            # Paid tier — requires credits
            {"id": "claude-haiku-4-5", "name": "Claude Haiku 4.5", "provider": "Anthropic", "tier": "paid", "context": 200000, "credits_1k_in": 1, "credits_1k_out": 3, "description": "Fast & affordable"},
            {"id": "claude-sonnet-4-6", "name": "Claude Sonnet 4.6", "provider": "Anthropic", "tier": "paid", "context": 200000, "credits_1k_in": 2, "credits_1k_out": 8, "description": "Best balance of speed & quality"},
            {"id": "claude-opus-4-6", "name": "Claude Opus 4.6", "provider": "Anthropic", "tier": "paid", "context": 200000, "credits_1k_in": 8, "credits_1k_out": 40, "description": "Most powerful reasoning"},
            {"id": "gpt-4o", "name": "GPT-4o", "provider": "OpenAI", "tier": "paid", "context": 128000, "credits_1k_in": 2, "credits_1k_out": 8, "description": "Versatile multimodal"},
            {"id": "gpt-4o-mini", "name": "GPT-4o Mini", "provider": "OpenAI", "tier": "paid", "context": 128000, "credits_1k_in": 1, "credits_1k_out": 2, "description": "Budget-friendly"},
        ],
        "default_free": "free-nemotron",
        "default_paid": "claude-sonnet-4-6",
    }


# =============================================================================
# Pricing info endpoint (for app to display)
# =============================================================================

@app.get("/v1/pricing")
async def get_pricing():
    """Return credit pricing per model and available model mappings."""
    return {
        "credit_value_usd": 0.01,
        "model_map": MODEL_MAP,
        "models": {
            model: {
                "input_per_1k": CREDIT_COST_PER_1K_INPUT.get(model, DEFAULT_COST_PER_1K_INPUT),
                "output_per_1k": CREDIT_COST_PER_1K_OUTPUT.get(model, DEFAULT_COST_PER_1K_OUTPUT),
            }
            for model in set(list(CREDIT_COST_PER_1K_INPUT.keys()) + list(CREDIT_COST_PER_1K_OUTPUT.keys()))
        },
        "free_daily_tokens": FREE_DAILY_TOKENS,
    }


# =============================================================================
# Entrypoint
# =============================================================================

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=LISTEN_HOST, port=LISTEN_PORT)
