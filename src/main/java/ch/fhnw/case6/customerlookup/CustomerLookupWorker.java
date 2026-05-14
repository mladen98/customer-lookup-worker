package ch.fhnw.case6.customerlookup;

import ch.fhnw.case6.customerlookup.client.ClientDataApiClient;
import ch.fhnw.case6.customerlookup.handler.CustomerLookupExternalTaskHandler;
import ch.fhnw.case6.customerlookup.service.CustomerLookupService;
import org.camunda.bpm.client.ExternalTaskClient;

public class CustomerLookupWorker {

    private static final String CAMUNDA_BASE_URL =
            "http://group6:p5TuHbjEadLeT6L@192.168.111.3:8080/engine-rest";

    private static final String CLIENT_DATA_API_URL =
            "http://localhost:8082";

    private static final String TOPIC =
            "group6_customer_lookup";

    public static void main(String[] args) {
        ClientDataApiClient apiClient = new ClientDataApiClient(CLIENT_DATA_API_URL);
        CustomerLookupService lookupService = new CustomerLookupService(apiClient);
        CustomerLookupExternalTaskHandler handler = new CustomerLookupExternalTaskHandler(lookupService);

        ExternalTaskClient client = ExternalTaskClient.create()
                .baseUrl(CAMUNDA_BASE_URL)
                .asyncResponseTimeout(1000)
                .build();

        client.subscribe(TOPIC)
                .lockDuration(1000)
                .handler(handler)
                .open();

        System.out.println("Customer Lookup Worker started.");
        System.out.println("Topic: " + TOPIC);
        System.out.println("Camunda: " + CAMUNDA_BASE_URL);
        System.out.println("Client Data API: " + CLIENT_DATA_API_URL);
    }
}