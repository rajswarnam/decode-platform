#include <stdio.h>

typedef struct {
    int acct_id;
    char acct_name[50];
    double balance;
} Account;

void update_balance(Account* acc, double amount) {
    acc->balance += amount;
}

int main() {
    Account customer_account;
    customer_account.acct_id = 12345;
    update_balance(&customer_account, 100.0);
    return 0;
}
