package com.obd.api.excpetion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.obd.api.auth.exception.EmailAlreadyInUseException;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.car.exception.LicensePlateAlreadyRegisteredException;
import com.obd.api.car.exception.ModelNotFoundException;
import com.obd.api.trip.exception.CarAlreadyOnATripException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail badCredentials(BadCredentialsException e) {
        var p = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        p.setTitle("Unauthorized");
        p.setDetail("Invalid email or password");
        return p;
    }

    @ExceptionHandler(EmailAlreadyInUseException.class)
    public ProblemDetail emailTaken(EmailAlreadyInUseException e) {
        var p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Conflict");
        p.setDetail("That email is already registered");
        return p;
    }

    @ExceptionHandler(ModelNotFoundException.class)
    public ProblemDetail modelNotFound(ModelNotFoundException e) {
        var p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setTitle("Not Found");
        p.setDetail("No such car model");
        return p;
    }

    @ExceptionHandler(LicensePlateAlreadyRegisteredException.class)
    public ProblemDetail licensePlateTaken(LicensePlateAlreadyRegisteredException e) {
        var p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Conflict");
        p.setDetail("You already have a car with that licence plate");
        return p;
    }

    @ExceptionHandler(CarNotFoundException.class)
    public ProblemDetail carNotFound(CarNotFoundException e) {
        var p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setTitle("Not Found");
        // Also the answer for a car that exists but belongs to someone else -
        // see CarNotFoundException.
        p.setDetail("No such car");
        return p;
    }

    @ExceptionHandler(CarAlreadyOnATripException.class)
    public ProblemDetail carAlreadyOnATrip(CarAlreadyOnATripException e) {
        var p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Conflict");
        p.setDetail("That car is already on a trip");
        return p;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail forbidden(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Not allowed");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        var p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Validation failed");
        p.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a)));
        return p;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail notFound(NoResourceFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No such endpoint");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}