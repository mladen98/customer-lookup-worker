package ch.fhnw.case6.customerlookup.exception;

public class CustomerDataNotFoundException extends RuntimeException {

    public CustomerDataNotFoundException(String customerReference) {
        super("Customer data not found for customerReference: " + customerReference);
    }
}