package app.assistant.domain.error

/**
 * Result of an operation that can fail in an expected way. Exceptions stay inside adapters;
 * every use case and port returns an Outcome with a sealed or enum error type.
 */
sealed interface Outcome<out T, out E> {
    data class Ok<T>(val value: T) : Outcome<T, Nothing>

    data class Err<E>(val error: E) : Outcome<Nothing, E>
}

val <T, E> Outcome<T, E>.isOk: Boolean get() = this is Outcome.Ok

fun <T, E> Outcome<T, E>.getOrNull(): T? = (this as? Outcome.Ok)?.value

fun <T, E> Outcome<T, E>.errorOrNull(): E? = (this as? Outcome.Err)?.error

inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

inline fun <T, E, F> Outcome<T, E>.mapError(transform: (E) -> F): Outcome<T, F> = when (this) {
    is Outcome.Ok -> this
    is Outcome.Err -> Outcome.Err(transform(error))
}

inline fun <T, E, R> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, E>): Outcome<R, E> = when (this) {
    is Outcome.Ok -> transform(value)
    is Outcome.Err -> this
}

inline fun <T, E> Outcome<T, E>.getOrElse(fallback: (E) -> T): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> fallback(error)
}
