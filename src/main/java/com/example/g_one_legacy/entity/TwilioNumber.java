package com.example.g_one_legacy.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "twilio_numbers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TwilioNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;
}
