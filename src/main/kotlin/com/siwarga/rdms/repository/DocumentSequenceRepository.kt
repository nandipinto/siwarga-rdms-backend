package com.siwarga.rdms.repository

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository

@Repository
class DocumentSequenceRepository(
    private val entityManager: EntityManager,
) {
    fun nextSequence(
        docType: String,
        year: Short,
    ): Long {
        val result =
            entityManager
                .createNativeQuery(
                    """
                    INSERT INTO document_sequence (doc_type, year, last_seq)
                    VALUES (:docType, :year, 1)
                    ON CONFLICT (doc_type, year) DO UPDATE SET last_seq = document_sequence.last_seq + 1
                    RETURNING last_seq
                    """.trimIndent(),
                ).setParameter("docType", docType)
                .setParameter("year", year)
                .singleResult
        return (result as Number).toLong()
    }
}
