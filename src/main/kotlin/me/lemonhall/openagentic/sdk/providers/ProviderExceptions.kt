package me.lemonhall.openagentic.sdk.providers

sealed class ProviderException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ProviderTimeoutException(
    message: String,
    cause: Throwable? = null,
) : ProviderException(message, cause)

class ProviderRateLimitException(
    message: String,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ProviderException(message, cause)

class ProviderHttpException(
    val status: Int,
    message: String,
    val body: String? = null,
    cause: Throwable? = null,
) : ProviderException(message, cause)

class ProviderInvalidResponseException(
    message: String,
    val raw: String? = null,
    cause: Throwable? = null,
) : ProviderException(message, cause)

