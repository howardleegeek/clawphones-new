package ai.clawphones.agent.config

enum class ModelTier { FREE, PAID }

data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val tier: ModelTier = ModelTier.PAID,
)

val availableModels = listOf(
    // Free tier — zero cost, always available
    ModelInfo("free-nemotron", "Nemotron Nano 30B", "Free · Fast", ModelTier.FREE),
    ModelInfo("free-nemotron-9b", "Nemotron Nano 9B", "Free · Fast", ModelTier.FREE),
    ModelInfo("free-trinity", "Trinity Large", "Free · Smart", ModelTier.FREE),
    // Paid tier — requires credits
    ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", "Fast · 1cr/1K", ModelTier.PAID),
    ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", "Balanced · 2cr/1K", ModelTier.PAID),
    ModelInfo("claude-opus-4-6", "Claude Opus 4.6", "Powerful · 8cr/1K", ModelTier.PAID),
    ModelInfo("gpt-4o", "GPT-4o", "Versatile · 2cr/1K", ModelTier.PAID),
    ModelInfo("gpt-4o-mini", "GPT-4o Mini", "Budget · 1cr/1K", ModelTier.PAID),
)

val freeModels = availableModels.filter { it.tier == ModelTier.FREE }
val paidModels = availableModels.filter { it.tier == ModelTier.PAID }

fun modelDisplayName(modelId: String?): String {
    if (modelId.isNullOrBlank()) return "Not configured"
    return availableModels.find { it.id == modelId }?.displayName ?: modelId
}
