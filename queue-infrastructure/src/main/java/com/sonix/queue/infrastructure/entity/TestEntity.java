package com.sonix.queue.infrastructure.entity;

import jakarta.persistence.*;
import lombok.Setter;

@Entity
@Table(name= " test_routing")
public class TestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String note;

}
