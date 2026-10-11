package com.vicinity24.core.linkedstore.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "platform_contract")
public class PlatformContract {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    private PlatformContractMode mode;

    @Column(name = "fee_percentage", nullable = false)
    private Double feePercentage;

    @Column(name = "platform_payout_connect_id", length = 255)
    private String platformPayoutConnectId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
