package ch.fhnw.case6.customerlookup.service;

import ch.fhnw.case6.customerlookup.client.ClientDataApiClient;
import ch.fhnw.case6.customerlookup.dto.CustomerData;

public class CustomerLookupService {

    private final ClientDataApiClient apiClient;

    public CustomerLookupService(ClientDataApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public CustomerData lookupCustomerData(String customerReference) {
        if (customerReference == null || customerReference.trim().isEmpty()) {
            throw new IllegalArgumentException("customerReference is missing");
        }

        CustomerData customerData = apiClient.getCustomerData(customerReference);

        customerData.setCustomerLookupSuccess(isComplete(customerData));

        return customerData;
    }

    private boolean isComplete(CustomerData data) {
        return data != null
                && isNotBlank(data.getDestination())
                && isNotBlank(data.getRecepientPhone())
                && isNotBlank(data.getEmail());
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}