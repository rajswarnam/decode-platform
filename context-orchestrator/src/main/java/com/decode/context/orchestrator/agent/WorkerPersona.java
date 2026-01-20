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
        "Focus on extracting IF-ELSE rules, identifying HARDCODED VALUES (Magic Numbers), and mapping variables to Business Terms. Ignore boilerplate."),

    BACKEND_C("C/C++ Backend Specialist", 
        "Focus on C/C++ source files, struct definitions, function implementations, and pointer operations. Analyze .c, .cpp, .h files."),

    BACKEND_ASPNET("ASP.NET Backend Specialist", 
        "Focus on ASP.NET Web Forms (.aspx), Code-Behind files (.aspx.cs, .aspx.vb), and server-side logic. Analyze .NET framework patterns."),

    CONFIG_ACLF("ACLF Configuration Specialist", 
        "Focus on ACLF (Attribute Configuration Language File) XML files. Map abstract business tags to physical C structure offsets. Analyze .aclf files."),

    FRONTEND_HTML("HTML/Web Specialist", 
        "Focus on HTML files, web forms, and static web content. Analyze .html, .htm files and web UI patterns.");

    private final String title;
    private final String description;

    WorkerPersona(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
}
