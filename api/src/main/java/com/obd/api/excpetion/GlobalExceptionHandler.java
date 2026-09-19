package com.obd.api.excpetion;

import com.obd.api.invitation.exception.*;
import com.obd.api.trip.exception.TripAlreadyEndedException;
import com.obd.api.trip.exception.TripNotFoundException;
import com.obd.api.user.exception.UserNotFoundException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.obd.api.auth.exception.EmailAlreadyInUseException;
import com.obd.api.car.exception.CarNotFoundException;
import com.obd.api.device.exception.DeviceAlreadyPairedException;
import com.obd.api.device.exception.DeviceNotFoundException;
import com.obd.api.device.exception.NoDevicePairedException;
import com.obd.api.car.exception.LicensePlateAlreadyRegisteredException;
import com.obd.api.car.exception.ModelNotFoundException;
import com.obd.api.model.exception.ModelAlreadyExistsException;
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
    @ExceptionHandler(ModelAlreadyExistsException.class)
    public ProblemDetail modelExists(ModelAlreadyExistsException e) {
        var p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Conflict");
        p.setDetail("That model already exists");
        return p;
    }
    @ExceptionHandler(AlreadyAMemberException.class)
    public ProblemDetail alreadyAMember(AlreadyAMemberException e) {
        var p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Conflict");
        p.setDetail("Already a Member of the Group");
        return p;
    }

    @ExceptionHandler(InvitationNotFoundException.class)
    public ProblemDetail invitationNotFound(InvitationNotFoundException e) {
        var p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setTitle("Not Found");
        p.setDetail("Invitation Not Found");
        return p;
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail userNotFound(UserNotFoundException e) {
        var p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setTitle("Not Found");
        p.setDetail("User not found");
        return p;
    }

    @ExceptionHandler(InvitationAlreadyAccepted.class)
    public ProblemDetail invitationAlreadyAccepted(InvitationAlreadyAccepted e) {
        var p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Conflict");
        p.setDetail("Invitation already accepted");
        return p;
    }

    @ExceptionHandler(InvitationExpiredException.class)
    public ProblemDetail invitationExpired(InvitationExpiredException e) {
        var p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Conflict");
        p.setDetail("Invitation Expired");
        return p;
    }

    @ExceptionHandler(TripNotFoundException.class)
    public ProblemDetail tripNotFound(TripNotFoundException e) {
        var p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setTitle("Not Found");
        // Also the answer for a trip that exists but is someone else's.
        p.setDetail("No such trip");
        return p;
    }

    @ExceptionHandler(TripAlreadyEndedException.class)
    public ProblemDetail tripAlreadyEnded(TripAlreadyEndedException e) {
        var p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Conflict");
        p.setDetail("That trip has already ended");
        return p;
    }

    @ExceptionHandler(NotAnAdminException.class)
    public ProblemDetail notAnAdmin(NotAnAdminException e) {
        var p = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        p.setTitle("Forbidden");
        p.setDetail("Not an Admin of the Group");
        return p;
    }
    @ExceptionHandler(NotAMemberException.class)
    public ProblemDetail notAMember(NotAMemberException e) {
        var p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setTitle("Not Found");
        // Also the answer for a group that does not exist - the id is not
        // confirmed to a non-member.
        p.setDetail("Not a Member of the Group");
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

    @ExceptionHandler(DeviceNotFoundException.class)
    public ProblemDetail deviceNotFound(DeviceNotFoundException e) {
        var p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setTitle("Not Found");
        // Also the answer for a serial paired to a car the caller may not see.
        p.setDetail("No such device");
        return p;
    }

    @ExceptionHandler(NoDevicePairedException.class)
    public ProblemDetail noDevicePaired(NoDevicePairedException e) {
        var p = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        p.setTitle("Not Found");
        p.setDetail("No device is paired to this car");
        return p;
    }

    @ExceptionHandler(DeviceAlreadyPairedException.class)
    public ProblemDetail deviceAlreadyPaired(DeviceAlreadyPairedException e) {
        var p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        p.setTitle("Conflict");
        p.setDetail("That device is paired to another car; unpair it there first");
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

    // A path variable that is not a UUID (GET /cars/not-a-uuid): the client's
    // fault, so 400 - not a 500 from the catch-all below.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
        var p = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        p.setTitle("Validation failed");
        p.setDetail("'%s' is not a valid value for '%s'".formatted(e.getValue(), e.getName()));
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