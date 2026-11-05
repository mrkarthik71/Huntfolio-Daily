package app.assistant.domain.error

/** Why one form field was rejected. Screens map each value to a string resource. */
enum class ValidationError {
    EMPTY,
    TOO_LONG,
    OUT_OF_RANGE,
    NOT_A_NUMBER,
    INVALID_DATE,
    IN_PAST,
    IN_FUTURE,
    DUPLICATE,
    LIMIT_REACHED,
    NOT_ALLOWED,
}

/** Field-level validation result used by every form (Goals, Tasks, Bills, ...). */
data class FieldErrors<F : Enum<F>>(val errors: Map<F, ValidationError>) {
    val isValid: Boolean get() = errors.isEmpty()

    operator fun get(field: F): ValidationError? = errors[field]

    companion object {
        fun <F : Enum<F>> none(): FieldErrors<F> = FieldErrors(emptyMap())
    }
}

/** Collects field errors in declaration order; the first error recorded for a field wins. */
class FieldErrorsBuilder<F : Enum<F>> {
    private val errors = LinkedHashMap<F, ValidationError>()

    fun add(field: F, error: ValidationError) {
        errors.putIfAbsent(field, error)
    }

    fun check(field: F, condition: Boolean, error: ValidationError) {
        if (!condition) add(field, error)
    }

    fun build(): FieldErrors<F> = FieldErrors(errors.toMap())
}

inline fun <F : Enum<F>> fieldErrors(block: FieldErrorsBuilder<F>.() -> Unit): FieldErrors<F> = FieldErrorsBuilder<F>().apply(block).build()
