package com.decode.vectorizer.service.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "projects")
@Data
public class Project {
    @Id
    private UUID id;
    private String name;
    private String domain;
}
