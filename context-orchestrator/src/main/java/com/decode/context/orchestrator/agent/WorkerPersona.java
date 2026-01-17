package com.decode.context.orchestrator.agent;

public enum WorkerPersona {
    BACKEND_JAVA("Java Backend Specialist", 
        "Focus on Spring Boot, JAX-RS, JPA Entities, and Controllers. Look for logic in Service classes."),
    
    FRONTEND_REACT("React Frontend Specialist", 
        "Focus on Redux Actions, React Components, Axios API clients, and State Management."),
    
    DATABASE_SQL("Database Architect", 
        "Focus on Schema definitions, Foreign Keys, Stored Procedures, and Data Models."),
    
    LEGACY_COBOL("Mainframe Specialist", 
        "Focus on COBOL WORKING-STORAGE, CICS commands, JCL, and COPYBOOKS."),
    
    GENERAL_ARCHITECT("General Architect", 
        "Focus on high-level patterns, directory structure, and configuration files."),

    LOGIC_EXTRACTOR("Business Logic Extractor", 
        "Focus on extracting IF-ELSE rules, identifying HARDCODED VALUES (Magic Numbers), and mapping variables to Business Terms. Ignore boilerplate.");

    private final String title;
    private final String description;

    WorkerPersona(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
}
