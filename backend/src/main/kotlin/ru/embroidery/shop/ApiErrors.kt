package ru.embroidery.shop

import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException

class ApiFailure(val status: HttpStatus, message: String) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw ApiFailure(HttpStatus.BAD_REQUEST, message)
fun missingOrder(): Nothing = throw ApiFailure(HttpStatus.NOT_FOUND, "Order not found")
fun conflict(message: String): Nothing = throw ApiFailure(HttpStatus.CONFLICT, message)

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiFailure::class)
    fun failure(error: ApiFailure): ProblemDetail = ProblemDetail.forStatusAndDetail(error.status, error.message!!)

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentNotValidException::class,
        MissingServletRequestPartException::class, MethodArgumentTypeMismatchException::class)
    fun invalidRequest(): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request")

    @ExceptionHandler(DuplicateKeyException::class)
    fun duplicate(): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Value already exists")

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun oversizedUpload(): ProblemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the upload limit"
    )
}
