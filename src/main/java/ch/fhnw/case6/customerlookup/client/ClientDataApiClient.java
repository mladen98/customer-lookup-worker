package ch.fhnw.case6.customerlookup.client;

import ch.fhnw.case6.customerlookup.dto.CustomerData;
import ch.fhnw.case6.customerlookup.exception.CustomerDataNotFoundException;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

public class ClientDataApiClient {

    private final String serviceUrl;

    public ClientDataApiClient(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public CustomerData getCustomerData(String customerReference) {
        Client client = ClientBuilder.newClient();

        try {
            Response response = client
                    .target(serviceUrl)
                    .path("/api/customers/" + customerReference)
                    .request(MediaType.APPLICATION_JSON_TYPE)
                    .get();

            int status = response.getStatus();

            if (status == 200) {
                return response.readEntity(CustomerData.class);
            }

            if (status == 404) {
                throw new CustomerDataNotFoundException(customerReference);
            }

            if (status >= 400 && status < 500) {
                throw new IllegalArgumentException("Invalid request to Client Data API. HTTP status: " + status);
            }

            throw new ProcessingException("Technical error from Client Data API. HTTP status: " + status);

        } finally {
            client.close();
        }
    }
}