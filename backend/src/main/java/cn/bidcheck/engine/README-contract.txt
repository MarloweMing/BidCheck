BidCheck deterministic engine contract (Java 8)

Public classes (no framework dependency):
CheckpointExtractor.extract(Domain.Document) -> List<Domain.Checkpoint>
FactExtractor.extract(Domain.Document) -> List<Domain.Fact>
CheckEngine.evaluate(Domain.Checkpoint, Domain.Document, List<Domain.Fact>) -> Domain.Evaluation

Checkpoint types:
VALUE_COMPARE: fields bidValidity, duration, warranty, totalPrice; operators >=, <=, =, >, <.
ENTITY_FIELD_CONSISTENCY: fields projectName, projectNumber, companyName, creditCode, plateNumber, personName.
MATERIAL_EXISTENCE: field material, expected material name; optional unit YEAR:2023,2024,2025.
DATE_VALIDITY: field certificateExpiry; operator >=; expected ISO date (bid deadline).
SEMANTIC_RESPONSE and SIGNATURE_SEAL: unsupported locally, return UNDETERMINED.
FULL_TEXT_REVIEW: operator REVIEW; fields fullTextConsistency, amountAccuracy, missingFields, signatureDate,
signingAfterOpening, typos, certificateCoverage. Signing/coverage expected is an ISO reference date (may be empty
until confirmed); a signing reference may include time, but text with date-only precision is compared by day.
The bounded global scans report cited anomalies as WARNING/FAIL. No match stays UNDETERMINED, never PASS.
Signing/opening comparisons use explicitly provided times on the same day; absent time/seconds are not invented.
Independent verification also preserves these global checks as unresolved without requesting model excerpts.
Disabled checkpoint: NOT_APPLICABLE. Unconfirmed checkpoint: UNDETERMINED.

Extracted candidate rules are confirmed=false and enabled=true. API must explicitly confirm.
Extracted categories are 形式检查, 资格检查, 响应检查, 全文信息检查, 效期检查.
Qualification candidates keep alternative materials, eligibility restrictions and branch conditions.
Wrapped and cross-page clauses are assembled without dropping requirements. A single source Evidence remains
a verbatim substring at its original location; cross-page expected text must not be fabricated as a one-page quote.
Scored certificate bullets are split individually; plan content is combined with the highest available score tier.
Built-in internal consistency candidates have expected="". Project identity candidates may have tender expected values.
Numeric values normalize to days (DAY), months (MONTH), or yuan (CNY), using BigDecimal.
Facts retain every textual occurrence, rawValue, normalizedValue, and source Evidence.
Evidence.page and locationType directly inherit Page.number and Page.locationType (PAGE, PARAGRAPH, or BLOCK).
Evidence.anchor uses p<number>-l<line>; paragraph number is never represented as a PDF page.
Vehicle strong entity keys use VIN:<value> or ENGINE:<value>. Unmatched/weak vehicles use UNKNOWN:<anchor>.
The engine links occurrences by shared VIN or engine number; owner/model alone cannot establish a FAIL.
Fact fields include plateNumber, vin, engineNumber, owner, vehicleType and certificateExpiry.
Materials require explicit attachment/provided evidence beyond merely mentioning the name or promising to provide it.
Unreadable/scanned/incompletely parsed content can yield UNDETERMINED rather than missing-material FAIL.

Statuses: PASS, FAIL, WARNING, UNDETERMINED, NOT_APPLICABLE.
Every FAIL/WARNING carries original bid evidence or the tender source for an absent required material.
No configured OCR/semantic/vision service is assumed; no factual assertions about unseen image content.
Dates use explicit ISO or Chinese year-month-day dates; certificate validity cannot be inferred without a deadline.
This is a deterministic baseline with limited Chinese templates, not a general semantic or OCR implementation.

Composition candidates are extracted from first/second-level headings only within an explicitly identified
tender document-format/composition section. Qualification, compliance and scoring sections produce confirmable
SEMANTIC_RESPONSE candidates (field qualification/compliance/scoring); local evaluation remains UNDETERMINED.
Numbered unfamiliar composition headings are retained; unnumbered headings require recognizable document titles.
Recognized alternate composition titles with substantive body text are cited for review, not failed as missing.
Certificate date checks reject not-yet-effective ranges; malformed or reversed ranges remain unresolved.
The personnel baseline groups explicit role labels (project manager, project lead, authorized representative).
Conflicting role names produce WARNING for human review, since a role can legitimately contain multiple people.
Built-in internal-consistency candidates use source=null; show "系统内置检查" instead of fabricating tender evidence.
