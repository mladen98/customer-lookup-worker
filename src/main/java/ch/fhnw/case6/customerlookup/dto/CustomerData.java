package ch.fhnw.case6.customerlookup.dto;

public class CustomerData {

    private String customerReference;
    private String destination;
    private String recepientPhone;
    private String email;
    private String country;
    private boolean customerLookupSuccess;

    public CustomerData() {
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public void setCustomerReference(String customerReference) {
        this.customerReference = customerReference;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getRecepientPhone() {
        return recepientPhone;
    }

    public void setRecepientPhone(String recepientPhone) {
        this.recepientPhone = recepientPhone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public boolean isCustomerLookupSuccess() {
        return customerLookupSuccess;
    }

    public void setCustomerLookupSuccess(boolean customerLookupSuccess) {
        this.customerLookupSuccess = customerLookupSuccess;
    }
}