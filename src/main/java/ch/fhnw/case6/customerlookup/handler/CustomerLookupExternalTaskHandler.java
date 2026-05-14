package ch.fhnw.case6.customerlookup.handler;

import ch.fhnw.case6.customerlookup.dto.CustomerData;
import ch.fhnw.case6.customerlookup.exception.CustomerDataNotFoundException;
import ch.fhnw.case6.customerlookup.service.CustomerLookupService;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;

import javax.ws.rs.ProcessingException;
import java.util.HashMap;
import java.util.Map;

public class CustomerLookupExternalTaskHandler implements ExternalTaskHandler {

    private static final int DEFAULT_RETRIES = 3;
    private static final long RETRY_TIMEOUT_MS = 60_000L;

    private final CustomerLookupService lookupService;

    public CustomerLookupExternalTaskHandler(CustomerLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        String customerReference = externalTask.getVariable("customerReference");

        try {
            CustomerData customerData = lookupService.lookupCustomerData(customerReference);

            Map<String, Object> variables = new HashMap<>();
            variables.put("destination", customerData.getDestination());
            variables.put("recepientPhone", customerData.getRecepientPhone());
            variables.put("email", customerData.getEmail());
            variables.put("customerLookupSuccess", customerData.isCustomerLookupSuccess());

            externalTaskService.complete(externalTask, variables);

            System.out.println("Customer lookup completed. customerReference="
                    + customerReference
                    + ", success="
                    + customerData.isCustomerLookupSuccess());

        } catch (IllegalArgumentException | CustomerDataNotFoundException ex) {
            completeWithFallback(externalTask, externalTaskService, ex.getMessage());

        } catch (ProcessingException ex) {
            handleTechnicalError(externalTask, externalTaskService, ex);

        } catch (Exception ex) {
            handleTechnicalError(externalTask, externalTaskService, ex);
        }
    }

    private void completeWithFallback(ExternalTask externalTask,
                                      ExternalTaskService externalTaskService,
                                      String reason) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("customerLookupSuccess", false);

        externalTaskService.complete(externalTask, variables);

        System.out.println("Customer lookup fallback. Reason: " + reason);
    }

    private void handleTechnicalError(ExternalTask externalTask,
                                      ExternalTaskService externalTaskService,
                                      Exception ex) {
        Integer retries = externalTask.getRetries();

        int remainingRetries;
        if (retries == null) {
            remainingRetries = DEFAULT_RETRIES - 1;
        } else {
            remainingRetries = retries - 1;
        }

        if (remainingRetries > 0) {
            externalTaskService.handleFailure(
                    externalTask,
                    "Technical error while loading customer data",
                    ex.getMessage(),
                    remainingRetries,
                    RETRY_TIMEOUT_MS
            );

            System.out.println("Technical error. Retry scheduled. Remaining retries: "
                    + remainingRetries);

        } else {
            Map<String, Object> variables = new HashMap<>();
            variables.put("customerLookupSuccess", false);

            externalTaskService.complete(externalTask, variables);

            System.out.println("Technical error after retries. Process continues with fallback.");
        }
    }
}