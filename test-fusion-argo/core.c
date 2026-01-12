struct User {
    char *username;
    int age;
};

void process_user(struct User *u) {
    // Audit log for security
    if (u->age > 18) {
        printf("Adult user processing: %s", u->username);
    }
}
