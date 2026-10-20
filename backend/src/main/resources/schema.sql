CREATE TABLE IF NOT EXISTS qc_record (
 id VARCHAR(64) PRIMARY KEY,
 kind VARCHAR(32) NOT NULL,
 project_id VARCHAR(64) NOT NULL,
 parent_id VARCHAR(64) NOT NULL,
 body CLOB NOT NULL,
 created_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_record_scope ON qc_record(kind,project_id,parent_id);
CREATE INDEX IF NOT EXISTS idx_record_project_time ON qc_record(kind,project_id,created_at,id);
CREATE TABLE IF NOT EXISTS qc_task (
 id VARCHAR(64) PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 task_type VARCHAR(32) NOT NULL,
 input_json CLOB NOT NULL,
 status VARCHAR(24) NOT NULL,
 stage VARCHAR(200) NOT NULL,
 progress INTEGER NOT NULL,
 attempt INTEGER NOT NULL,
 error VARCHAR(2000),
 created_at VARCHAR(40) NOT NULL,
 updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_task_status ON qc_task(status,created_at);
CREATE INDEX IF NOT EXISTS idx_task_project_time ON qc_task(project_id,created_at,id);
ALTER TABLE qc_task ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(64);
ALTER TABLE qc_task ADD COLUMN IF NOT EXISTS lease_until BIGINT;
CREATE INDEX IF NOT EXISTS idx_task_lease ON qc_task(status,lease_until);

-- Cross-process single-flight ownership for immutable evidence operations. The result itself
-- remains in qc_record; this row is only a renewable, expiring execution claim.
CREATE TABLE IF NOT EXISTS qc_evidence_operation_lease (
 operation_key VARCHAR(64) PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 owner VARCHAR(64) NOT NULL,
 lease_until BIGINT NOT NULL,
 acquired_at BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_evidence_operation_lease_expiry ON qc_evidence_operation_lease(lease_until);

-- Relational immutable fact ledger v3
CREATE TABLE IF NOT EXISTS qc_entity (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 entity_id VARCHAR(64) NOT NULL,
 project_id VARCHAR(64) NOT NULL,
 engine_key VARCHAR(64) NOT NULL,
 engine_identity VARCHAR(200) NOT NULL,
 entity_type VARCHAR(32) NOT NULL,
 identity_strength VARCHAR(24) NOT NULL,
 display_name CLOB NOT NULL,
 identifiers_json CLOB NOT NULL,
 claims_json CLOB NOT NULL,
 audit_json CLOB NOT NULL,
 content_hash VARCHAR(64) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_entity_scope ON qc_entity(project_id,engine_key,entity_id);
CREATE TABLE IF NOT EXISTS qc_fact_occurrence (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 engine_key VARCHAR(64) NOT NULL,
 engine_identity VARCHAR(200) NOT NULL,
 entity_key VARCHAR(64) NOT NULL,
 kind VARCHAR(32) NOT NULL,
 subject_text CLOB NOT NULL,
 field_name CLOB NOT NULL,
 field_hash VARCHAR(64) NOT NULL,
 raw_value CLOB NOT NULL,
 normalized_value CLOB NOT NULL,
 unit CLOB NOT NULL,
 basis CLOB NOT NULL,
 scope_type VARCHAR(32) NOT NULL,
 model_fact_id VARCHAR(200) NOT NULL,
 attributes_json CLOB NOT NULL,
 entity_audit_json CLOB NOT NULL,
 layout_identity CLOB NOT NULL,
 created_at VARCHAR(40) NOT NULL,
 ledger_version VARCHAR(100) NOT NULL,
 document_id VARCHAR(200) NOT NULL,
 document_version INTEGER NOT NULL,
 document_hash VARCHAR(64) NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 source_domain VARCHAR(32) NOT NULL,
 extraction_method VARCHAR(100) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_fact_scope ON qc_fact_occurrence(project_id,engine_key,kind);
CREATE INDEX IF NOT EXISTS idx_fact_entity_field ON qc_fact_occurrence(entity_key,field_hash);
CREATE INDEX IF NOT EXISTS idx_fact_document ON qc_fact_occurrence(project_id,document_id,document_version);
CREATE TABLE IF NOT EXISTS qc_evidence (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 project_id VARCHAR(64) NOT NULL,
 engine_key VARCHAR(64) NOT NULL,
 document_id VARCHAR(200) NOT NULL,
 document_name CLOB NOT NULL,
 document_hash VARCHAR(64) NOT NULL,
 document_version INTEGER NOT NULL,
 parser_fingerprint VARCHAR(64) NOT NULL,
 source_fingerprint VARCHAR(64) NOT NULL,
 location_type VARCHAR(32) NOT NULL,
 location_number INTEGER NOT NULL,
 quote_text CLOB NOT NULL,
 anchor_text CLOB NOT NULL,
 confidence DOUBLE NOT NULL,
 content_hash VARCHAR(64) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_evidence_source ON qc_evidence(project_id,engine_key,source_fingerprint);
CREATE INDEX IF NOT EXISTS idx_evidence_document ON qc_evidence(project_id,document_id,document_version,location_number);
CREATE TABLE IF NOT EXISTS qc_entity_link (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 occurrence_id VARCHAR(64) NOT NULL,
 project_id VARCHAR(64) NOT NULL,
 engine_key VARCHAR(64) NOT NULL,
 subject_entity_key VARCHAR(64) NOT NULL,
 kind VARCHAR(32) NOT NULL,
 predicate CLOB NOT NULL,
 predicate_hash VARCHAR(64) NOT NULL,
 object_text CLOB NOT NULL,
 scope_type VARCHAR(32) NOT NULL,
 resolution VARCHAR(32) NOT NULL,
 attributes_json CLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_link_entity ON qc_entity_link(project_id,engine_key,subject_entity_key);
CREATE INDEX IF NOT EXISTS idx_link_occurrence ON qc_entity_link(occurrence_id);
CREATE TABLE IF NOT EXISTS qc_fact_evidence (
 occurrence_id VARCHAR(64) NOT NULL, evidence_id VARCHAR(64) NOT NULL, source_id VARCHAR(200) NOT NULL, source_order INTEGER NOT NULL,
 PRIMARY KEY(occurrence_id,source_order), UNIQUE(occurrence_id,source_id)
);
CREATE INDEX IF NOT EXISTS idx_fact_evidence_reverse ON qc_fact_evidence(evidence_id,occurrence_id);
