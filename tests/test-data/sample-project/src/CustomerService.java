package com.legacy.banking;

public class CustomerService {
    private String customerId;
    private String accountNumber;
    
    public void processTransaction(String txnId, double amount) {
        // Legacy transaction processing
        System.out.println("Processing: " + txnId);
    }
    
    public String getCustomerBalance(String custId) {
        return "Balance for " + custId;
    }
}
