package com.toy.backend.ledger.inbound

import jakarta.validation.constraints.NotBlank

data class InboundRequest(
    @field:NotBlank
    val text: String,
)
