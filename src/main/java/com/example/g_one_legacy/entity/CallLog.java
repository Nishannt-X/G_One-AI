package com.example.g_one_legacy.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "call_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "twilio_call_sid", nullable = false)
    private String twilioCallSid;

    @Column(columnDefinition = "LONGTEXT")
    private String transcript;

    @Column(name = "booking_confirmed")
    private Boolean bookingConfirmed;

    @Column(name = "payment_link")
    private String paymentLink;
}

