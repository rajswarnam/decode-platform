package com.decode.context.orchestrator.agent;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WorkerTask {
    private String taskId;
    private WorkerPersona persona;
    private String focusArea; // e.g., "procurement-backend" or "All Controllers"
    private String specificQuestion; // e.g., "Analyze Authentication Logic"
    
    // Output State
    private String status; // PENDING, COMPLETED, FAILED, RETRYING
    private String report;
    private String validationErrors;
    private int attemptCount;
}
