CREATE TABLE IF NOT EXISTS qc_record (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 kind VARCHAR(32) NOT NULL,
 project_id VARCHAR(64) NOT NULL,
 parent_id VARCHAR(64) NOT NULL,
 body LONGTEXT NOT NULL,
 created_at BIGINT NOT NULL,
 INDEX idx_record_scope (kind,project_id,parent_id),
 INDEX idx_record_project_time (kind,project_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS qc_task (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 task_type VARCHAR(32) NOT NULL,
 input_json LONGTEXT NOT NULL,
 status VARCHAR(24) NOT NULL,
 stage VARCHAR(200) NOT NULL,
 progress INT NOT NULL,
 attempt INT NOT NULL,
 error VARCHAR(2000),
 created_at VARCHAR(40) NOT NULL,
 updated_at VARCHAR(40) NOT NULL,
 lease_owner VARCHAR(64),
 lease_until BIGINT,
 INDEX idx_task_status (status,created_at),
 INDEX idx_task_lease (status,lease_until),
 INDEX idx_task_project_time (project_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cross-process single-flight ownership for immutable evidence operations. The result itself
-- remains in qc_record; this row is only a renewable, expiring execution claim.
CREATE TABLE IF NOT EXISTS qc_evidence_operation_lease (
 operation_key VARCHAR(64) NOT NULL PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 owner VARCHAR(64) NOT NULL,
 lease_until BIGINT NOT NULL,
 acquired_at BIGINT NOT NULL,
 INDEX idx_evidence_operation_lease_expiry (lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Relational immutable fact ledger v3
CREATE TABLE IF NOT EXISTS qc_entity (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 entity_id VARCHAR(64) NOT NULL,
 project_id VARCHAR(64) NOT NULL,
 engine_key VARCHAR(64) NOT NULL,
 engine_identity VARCHAR(200) NOT NULL,
 entity_type VARCHAR(32) NOT NULL,
 identity_strength VARCHAR(24) NOT NULL,
 display_name LONGTEXT NOT NULL,
 identifiers_json LONGTEXT NOT NULL,
 claims_json LONGTEXT NOT NULL,
 audit_json LONGTEXT NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 INDEX idx_entity_scope (project_id,engine_key,entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS qc_fact_occurrence (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 engine_key VARCHAR(64) NOT NULL,
 engine_identity VARCHAR(200) NOT NULL,
 entity_key VARCHAR(64) NOT NULL,
 kind VARCHAR(32) NOT NULL,
 subject_text LONGTEXT NOT NULL,
 field_name LONGTEXT NOT NULL,
 field_hash VARCHAR(64) NOT NULL,
 raw_value LONGTEXT NOT NULL,
 normalized_value LONGTEXT NOT NULL,
 unit LONGTEXT NOT NULL,
 basis LONGTEXT NOT NULL,
 scope_type VARCHAR(32) NOT NULL,
 model_fact_id VARCHAR(200) NOT NULL,
 attributes_json LONGTEXT NOT NULL,
 entity_audit_json LONGTEXT NOT NULL,
 layout_identity LONGTEXT NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 ledger_version VARCHAR(100) NOT NULL,
 document_id VARCHAR(200) NOT NULL,
 document_version INTEGER NOT NULL,
 document_hash VARCHAR(64) NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 source_domain VARCHAR(32) NOT NULL,
 extraction_method VARCHAR(100) NOT NULL,
 INDEX idx_fact_scope (project_id,engine_key,kind),
 INDEX idx_fact_entity_field (entity_key,field_hash),
 INDEX idx_fact_document (project_id,document_id,document_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS qc_evidence (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 engine_key VARCHAR(64) NOT NULL,
 document_id VARCHAR(200) NOT NULL,
 document_name LONGTEXT NOT NULL,
 document_hash VARCHAR(64) NOT NULL,
 document_version INTEGER NOT NULL,
 parser_fingerprint VARCHAR(64) NOT NULL,
 source_fingerprint VARCHAR(64) NOT NULL,
 location_type VARCHAR(32) NOT NULL,
 location_number INTEGER NOT NULL,
 quote_text LONGTEXT NOT NULL,
 anchor_text LONGTEXT NOT NULL,
 confidence DOUBLE NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 INDEX idx_evidence_source (project_id,engine_key,source_fingerprint),
 INDEX idx_evidence_document (project_id,document_id,document_version,location_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS qc_entity_link (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 occurrence_id VARCHAR(64) NOT NULL,
 project_id VARCHAR(64) NOT NULL,
 engine_key VARCHAR(64) NOT NULL,
 subject_entity_key VARCHAR(64) NOT NULL,
 kind VARCHAR(32) NOT NULL,
 predicate LONGTEXT NOT NULL,
 predicate_hash VARCHAR(64) NOT NULL,
 object_text LONGTEXT NOT NULL,
 scope_type VARCHAR(32) NOT NULL,
 resolution VARCHAR(32) NOT NULL,
 attributes_json LONGTEXT NOT NULL,
 INDEX idx_link_entity (project_id,engine_key,subject_entity_key),
 INDEX idx_link_occurrence (occurrence_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS qc_fact_evidence (
 occurrence_id VARCHAR(64) NOT NULL, evidence_id VARCHAR(64) NOT NULL, source_id VARCHAR(200) NOT NULL, source_order INTEGER NOT NULL,
 PRIMARY KEY(occurrence_id,source_order), UNIQUE(occurrence_id,source_id),
 INDEX idx_fact_evidence_reverse(evidence_id,occurrence_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
