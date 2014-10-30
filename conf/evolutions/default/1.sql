# --- Created by Ebean DDL
# To stop Ebean DDL generation, remove this comment and start using Evolutions

# --- !Ups

create table ix_core_acl (
  id                        bigint not null,
  perm                      integer,
  constraint ck_ix_core_acl_perm check (perm in (0,1,2,3,4,5,6)),
  constraint pk_ix_core_acl primary key (id))
;

create table ix_ncats_clinical_arm (
  id                        bigint not null,
  label                     varchar(255),
  description               clob,
  type                      integer,
  constraint ck_ix_ncats_clinical_arm_type check (type in (0,1,2,3,4,5,6)),
  constraint pk_ix_ncats_clinical_arm primary key (id))
;

create table ix_core_attribute (
  id                        bigint not null,
  name                      varchar(255),
  value                     varchar(1024),
  resource_id               bigint,
  constraint pk_ix_core_attribute primary key (id))
;

create table ix_ncats_clinical_trial (
  id                        bigint not null,
  nct_id                    varchar(15),
  title                     varchar(1024),
  official_title            varchar(2048),
  summary                   clob,
  description               clob,
  first_received_date       timestamp,
  last_changed_date         timestamp,
  verification_date         timestamp,
  has_results               boolean,
  status                    integer,
  phase                     integer,
  eligibility_id            bigint,
  constraint ck_ix_ncats_clinical_trial_status check (status in (0,1,2,3,4,5,6)),
  constraint ck_ix_ncats_clinical_trial_phase check (phase in (0,1,2,3,4,5,6,7,8,9)),
  constraint uq_ix_ncats_clinical_trial_nct_i unique (nct_id),
  constraint pk_ix_ncats_clinical_trial primary key (id))
;

create table ix_ncats_clinical_cohort (
  id                        bigint not null,
  label                     varchar(255),
  description               clob,
  constraint pk_ix_ncats_clinical_cohort primary key (id))
;

create table ix_ncats_clinical_condition (
  id                        bigint not null,
  name                      varchar(255),
  is_rare_disease           boolean,
  constraint pk_ix_ncats_clinical_condition primary key (id))
;

create table ix_core_curation (
  id                        bigint not null,
  curator_id                bigint,
  status                    integer,
  timestamp                 timestamp,
  constraint ck_ix_core_curation_status check (status in (0,1,2)),
  constraint pk_ix_core_curation primary key (id))
;

create table ix_core_etag (
  id                        bigint not null,
  etag                      varchar(16),
  uri                       varchar(4000),
  path                      varchar(255),
  method                    varchar(10),
  sha1                      varchar(40),
  total                     integer,
  count                     integer,
  skip                      integer,
  top                       integer,
  status                    integer,
  timestamp                 timestamp,
  modified                  timestamp,
  query                     varchar(2048),
  constraint pk_ix_core_etag primary key (id))
;

create table ix_core_etagref (
  id                        bigint not null,
  etag_id                   bigint,
  ref_id                    bigint,
  constraint pk_ix_core_etagref primary key (id))
;

create table ix_core_edit (
  id                        bigint not null,
  type                      varchar(255),
  refid                     bigint,
  timestamp                 timestamp,
  principal_id              bigint,
  path                      varchar(1024),
  comments                  clob,
  old_value                 clob,
  new_value                 clob,
  constraint pk_ix_core_edit primary key (id))
;

create table ix_ncats_clinical_eligibility (
  id                        bigint not null,
  gender                    varchar(32),
  min_age                   varchar(255),
  max_age                   varchar(255),
  healthy_volunteers        boolean,
  constraint pk_ix_ncats_clinical_eligibility primary key (id))
;

create table ix_idg_entity (
  dtype                     varchar(10) not null,
  id                        bigint not null,
  name                      varchar(1024),
  description               clob,
  constraint pk_ix_idg_entity primary key (id))
;

create table ix_core_event (
  id                        bigint not null,
  title                     varchar(1024),
  description               clob,
  url                       varchar(1024),
  start                     timestamp,
  end                       timestamp,
  is_duration               boolean,
  constraint pk_ix_core_event primary key (id))
;

create table ix_core_figure (
  id                        bigint not null,
  caption                   varchar(255),
  mime_type                 varchar(255),
  url                       varchar(1024),
  data                      blob,
  size                      integer,
  sha1                      varchar(140),
  constraint pk_ix_core_figure primary key (id))
;

create table ix_ncats_funding (
  id                        bigint not null,
  grant_id                  bigint not null,
  ic                        varchar(255),
  amount                    integer,
  constraint pk_ix_ncats_funding primary key (id))
;

create table ix_core_gene (
  id                        bigint not null,
  name                      varchar(255),
  constraint pk_ix_core_gene primary key (id))
;

create table ix_ncats_grant (
  id                        bigint not null,
  application_id            bigint,
  activity                  varchar(255),
  administering_ic          varchar(255),
  application_type          integer,
  is_arra_funded            boolean,
  award_notice_date         timestamp,
  budget_start              timestamp,
  budget_end                timestamp,
  cfda_code                 integer,
  foa_number                varchar(255),
  full_project_num          varchar(255),
  subproject_id             bigint,
  fiscal_year               integer,
  ic_name                   varchar(255),
  ed_inst_type              varchar(255),
  nih_spending_cats         varchar(255),
  program_officer_name      varchar(255),
  project_start             timestamp,
  project_end               timestamp,
  core_project_num          varchar(255),
  project_title             varchar(255),
  public_health_relevance   clob,
  serial_number             bigint,
  study_section             varchar(255),
  study_section_name        varchar(255),
  suffix                    varchar(255),
  funding_mechanism         varchar(255),
  total_cost                integer,
  total_cost_subproject     integer,
  project_abstract          clob,
  constraint pk_ix_ncats_grant primary key (id))
;

create table ix_core_group (
  id                        bigint not null,
  name                      varchar(255),
  constraint uq_ix_core_group_name unique (name),
  constraint pk_ix_core_group primary key (id))
;

create table ix_ncats_clinical_intervention (
  id                        bigint not null,
  name                      varchar(255),
  description               clob,
  type                      integer,
  constraint ck_ix_ncats_clinical_intervention_type check (type in (0,1,2,3,4,5,6,7,8)),
  constraint pk_ix_ncats_clinical_interventio primary key (id))
;

create table ix_core_investigator (
  id                        bigint not null,
  name                      varchar(255),
  pi_id                     bigint,
  organization_id           bigint,
  role                      integer,
  constraint ck_ix_core_investigator_role check (role in (0,1)),
  constraint pk_ix_core_investigator primary key (id))
;

create table ix_core_journal (
  id                        bigint not null,
  issn                      varchar(10),
  volume                    varchar(255),
  issue                     varchar(255),
  year                      integer,
  month                     varchar(10),
  title                     varchar(256),
  iso_abbr                  varchar(255),
  factor                    double,
  constraint pk_ix_core_journal primary key (id))
;

create table ix_core_organization (
  id                        bigint not null,
  duns                      varchar(10),
  name                      varchar(255),
  department                varchar(255),
  city                      varchar(255),
  state                     varchar(20),
  zipcode                   varchar(15),
  district                  varchar(255),
  country                   varchar(255),
  fips                      varchar(3),
  longitude                 double,
  latitude                  double,
  constraint pk_ix_core_organization primary key (id))
;

create table ix_ncats_clinical_outcome (
  id                        bigint not null,
  type                      integer,
  measure                   varchar(255),
  timeframe                 varchar(255),
  description               clob,
  safety_issue              boolean,
  constraint ck_ix_ncats_clinical_outcome_type check (type in (0,1,2,3)),
  constraint pk_ix_ncats_clinical_outcome primary key (id))
;

create table ix_core_payload (
  id                        bigint not null,
  name                      varchar(1024),
  sha1                      varchar(40),
  mime                      varchar(128),
  size                      bigint,
  created                   timestamp,
  constraint pk_ix_core_payload primary key (id))
;

create table ix_core_predicate (
  dtype                     varchar(10) not null,
  id                        bigint not null,
  name                      varchar(255) not null,
  object_id                 bigint,
  constraint pk_ix_core_predicate primary key (id))
;

create table ix_core_principal (
  dtype                     varchar(10) not null,
  id                        bigint not null,
  username                  varchar(255),
  email                     varchar(255),
  admin                     boolean,
  uri                       varchar(1024),
  pkey                      varchar(256),
  selfie_id                 bigint,
  lastname                  varchar(255),
  forename                  varchar(255),
  initials                  varchar(255),
  affiliation               varchar(1024),
  orcid                     varchar(20),
  url                       varchar(1024),
  ncats_employee            boolean,
  dn                        varchar(1024),
  uid                       bigint,
  phone                     varchar(15),
  biography                 clob,
  title                     varchar(255),
  is_lead                   boolean,
  dept                      integer,
  role                      integer,
  constraint ck_ix_core_principal_dept check (dept in (0,1,2,3)),
  constraint ck_ix_core_principal_role check (role in (0,1,2,3,4)),
  constraint uq_ix_core_principal_pkey unique (pkey),
  constraint pk_ix_core_principal primary key (id))
;

create table ix_core_processingstatus (
  id                        bigint not null,
  status                    integer,
  message                   varchar(4000),
  payload_id                bigint,
  constraint ck_ix_core_processingstatus_status check (status in (0,1,2)),
  constraint pk_ix_core_processingstatus primary key (id))
;

create table ix_ncats_project (
  id                        bigint not null,
  title                     varchar(1024),
  objective                 clob,
  scope                     clob,
  opportunities             clob,
  team                      varchar(255),
  acl_id                    bigint,
  curation_id               bigint,
  constraint pk_ix_ncats_project primary key (id))
;

create table ix_core_pubauthor (
  id                        bigint not null,
  position                  integer,
  is_last                   boolean,
  correspondence            boolean,
  author_id                 bigint,
  constraint pk_ix_core_pubauthor primary key (id))
;

create table ix_core_publication (
  id                        bigint not null,
  pmid                      bigint,
  pmcid                     varchar(255),
  title                     varchar(1024),
  pages                     varchar(255),
  doi                       varchar(255),
  abstract_text             clob,
  journal_id                bigint,
  constraint uq_ix_core_publication_pmid unique (pmid),
  constraint uq_ix_core_publication_pmcid unique (pmcid),
  constraint pk_ix_core_publication primary key (id))
;

create table ix_core_resource (
  id                        bigint not null,
  name                      varchar(255),
  url                       varchar(1024),
  modifier                  integer,
  constraint ck_ix_core_resource_modifier check (modifier in (0,1,2)),
  constraint uq_ix_core_resource_name unique (name),
  constraint pk_ix_core_resource primary key (id))
;

create table ix_core_role (
  id                        bigint not null,
  role                      integer,
  principal_id              bigint,
  constraint ck_ix_core_role_role check (role in (0,1,2,3)),
  constraint pk_ix_core_role primary key (id))
;

create table ix_core_stitch (
  id                        bigint not null,
  name                      varchar(255),
  impl                      varchar(1024),
  description               clob,
  constraint pk_ix_core_stitch primary key (id))
;

create table ix_core_value (
  dtype                     varchar(10) not null,
  id                        bigint not null,
  curation_id               bigint,
  vnum                      double,
  term                      varchar(255),
  vstr                      varchar(1024),
  vint                      bigint,
  major_topic               boolean,
  heading                   varchar(1024),
  constraint pk_ix_core_value primary key (id))
;

create table ix_core_xref (
  id                        bigint not null,
  type                      varchar(512) not null,
  ref_id                    bigint,
  constraint pk_ix_core_xref primary key (id))
;


create table ix_core_acl_principal (
  ix_core_acl_id                 bigint not null,
  ix_core_principal_id           bigint not null,
  constraint pk_ix_core_acl_principal primary key (ix_core_acl_id, ix_core_principal_id))
;

create table ix_core_acl_group (
  ix_core_acl_id                 bigint not null,
  ix_core_group_id               bigint not null,
  constraint pk_ix_core_acl_group primary key (ix_core_acl_id, ix_core_group_id))
;

create table ix_ncats_clinical_trial_keyword (
  ix_ncats_clinical_trial_id     bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk_ix_ncats_clinical_trial_keyword primary key (ix_ncats_clinical_trial_id, ix_core_value_id))
;

create table ix_ncats_clinical_trial_sponsor (
  ix_ncats_clinical_trial_id     bigint not null,
  ix_core_organization_id        bigint not null,
  constraint pk_ix_ncats_clinical_trial_sponsor primary key (ix_ncats_clinical_trial_id, ix_core_organization_id))
;

create table ix_ncats_clinical_trial_intervention (
  ix_ncats_clinical_trial_id     bigint not null,
  ix_ncats_clinical_intervention_id bigint not null,
  constraint pk_ix_ncats_clinical_trial_intervention primary key (ix_ncats_clinical_trial_id, ix_ncats_clinical_intervention_id))
;

create table ix_ncats_clinical_trial_condition (
  ix_ncats_clinical_trial_id     bigint not null,
  ix_ncats_clinical_condition_id bigint not null,
  constraint pk_ix_ncats_clinical_trial_condition primary key (ix_ncats_clinical_trial_id, ix_ncats_clinical_condition_id))
;

create table ix_ncats_clinical_trial_outcome (
  ix_ncats_clinical_trial_id     bigint not null,
  ix_ncats_clinical_outcome_id   bigint not null,
  constraint pk_ix_ncats_clinical_trial_outcome primary key (ix_ncats_clinical_trial_id, ix_ncats_clinical_outcome_id))
;

create table ix_ncats_clincial_trial_location (
  ix_ncats_clinical_trial_id     bigint not null,
  ix_core_organization_id        bigint not null,
  constraint pk_ix_ncats_clincial_trial_location primary key (ix_ncats_clinical_trial_id, ix_core_organization_id))
;

create table _ix_ncats_cca46885_1 (
  ix_ncats_clinical_condition_synonym_id bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk__ix_ncats_cca46885_1 primary key (ix_ncats_clinical_condition_synonym_id, ix_core_value_id))
;

create table _ix_ncats_cca46885_2 (
  ix_ncats_clinical_condition_keyword_id bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk__ix_ncats_cca46885_2 primary key (ix_ncats_clinical_condition_keyword_id, ix_core_value_id))
;

create table _ix_ncats_cca46885_3 (
  ix_ncats_clinical_condition_wikipedia_id bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk__ix_ncats_cca46885_3 primary key (ix_ncats_clinical_condition_wikipedia_id, ix_core_value_id))
;

create table _ix_ncats_840372f9_1 (
  ix_ncats_clinical_eligibility_inclusion_id bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk__ix_ncats_840372f9_1 primary key (ix_ncats_clinical_eligibility_inclusion_id, ix_core_value_id))
;

create table _ix_ncats_840372f9_2 (
  ix_ncats_clinical_eligibility_exclusion_id bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk__ix_ncats_840372f9_2 primary key (ix_ncats_clinical_eligibility_exclusion_id, ix_core_value_id))
;

create table ix_idg_target_synonym (
  ix_idg_entity_id               bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk_ix_idg_target_synonym primary key (ix_idg_entity_id, ix_core_value_id))
;

create table ix_idg_target_annotation (
  ix_idg_entity_id               bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk_ix_idg_target_annotation primary key (ix_idg_entity_id, ix_core_value_id))
;

create table ix_idg_target_publication (
  ix_idg_entity_id               bigint not null,
  ix_core_publication_id         bigint not null,
  constraint pk_ix_idg_target_publication primary key (ix_idg_entity_id, ix_core_publication_id))
;

create table ix_idg_target_predicate (
  ix_idg_entity_id               bigint not null,
  ix_core_predicate_id           bigint not null,
  constraint pk_ix_idg_target_predicate primary key (ix_idg_entity_id, ix_core_predicate_id))
;

create table ix_core_event_figure (
  ix_core_event_id               bigint not null,
  ix_core_figure_id              bigint not null,
  constraint pk_ix_core_event_figure primary key (ix_core_event_id, ix_core_figure_id))
;

create table ix_core_gene_synonym (
  ix_core_gene_id                bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk_ix_core_gene_synonym primary key (ix_core_gene_id, ix_core_value_id))
;

create table ix_ncats_grant_investigator (
  ix_ncats_grant_id              bigint not null,
  ix_core_investigator_id        bigint not null,
  constraint pk_ix_ncats_grant_investigator primary key (ix_ncats_grant_id, ix_core_investigator_id))
;

create table ix_ncats_grant_keyword (
  ix_ncats_grant_id              bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk_ix_ncats_grant_keyword primary key (ix_ncats_grant_id, ix_core_value_id))
;

create table ix_ncats_grant_publication (
  ix_ncats_grant_id              bigint not null,
  ix_core_publication_id         bigint not null,
  constraint pk_ix_ncats_grant_publication primary key (ix_ncats_grant_id, ix_core_publication_id))
;

create table ix_core_group_principal (
  ix_core_group_id               bigint not null,
  ix_core_principal_id           bigint not null,
  constraint pk_ix_core_group_principal primary key (ix_core_group_id, ix_core_principal_id))
;

create table _ix_ncats_4a162ae3_1 (
  ix_ncats_clinical_intervention_id bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk__ix_ncats_4a162ae3_1 primary key (ix_ncats_clinical_intervention_id, ix_core_value_id))
;

create table _ix_ncats_4a162ae3_2 (
  ix_ncats_clinical_intervention_id bigint not null,
  ix_ncats_clinical_arm_id       bigint not null,
  constraint pk__ix_ncats_4a162ae3_2 primary key (ix_ncats_clinical_intervention_id, ix_ncats_clinical_arm_id))
;

create table _ix_ncats_4a162ae3_3 (
  ix_ncats_clinical_intervention_id bigint not null,
  ix_ncats_clinical_cohort_id    bigint not null,
  constraint pk__ix_ncats_4a162ae3_3 primary key (ix_ncats_clinical_intervention_id, ix_ncats_clinical_cohort_id))
;

create table ix_core_payload_attribute (
  ix_core_payload_id             bigint not null,
  ix_core_attribute_id           bigint not null,
  constraint pk_ix_core_payload_attribute primary key (ix_core_payload_id, ix_core_attribute_id))
;

create table ix_core_predicate_annotation (
  ix_core_predicate_id           bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk_ix_core_predicate_annotation primary key (ix_core_predicate_id, ix_core_value_id))
;

create table ix_ncats_project_annotation (
  ix_ncats_project_id            bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk_ix_ncats_project_annotation primary key (ix_ncats_project_id, ix_core_value_id))
;

create table ix_ncats_project_member (
  ix_ncats_project_id            bigint not null,
  ix_core_principal_id           bigint not null,
  constraint pk_ix_ncats_project_member primary key (ix_ncats_project_id, ix_core_principal_id))
;

create table ix_ncats_project_collaborator (
  ix_ncats_project_id            bigint not null,
  ix_core_principal_id           bigint not null,
  constraint pk_ix_ncats_project_collaborator primary key (ix_ncats_project_id, ix_core_principal_id))
;

create table ix_ncats_project_figure (
  ix_ncats_project_id            bigint not null,
  ix_core_figure_id              bigint not null,
  constraint pk_ix_ncats_project_figure primary key (ix_ncats_project_id, ix_core_figure_id))
;

create table ix_ncats_project_milestone (
  ix_ncats_project_id            bigint not null,
  ix_core_event_id               bigint not null,
  constraint pk_ix_ncats_project_milestone primary key (ix_ncats_project_id, ix_core_event_id))
;

create table ix_ncats_project_publication (
  ix_ncats_project_id            bigint not null,
  ix_core_publication_id         bigint not null,
  constraint pk_ix_ncats_project_publication primary key (ix_ncats_project_id, ix_core_publication_id))
;

create table ix_core_publication_keyword (
  ix_core_publication_id         bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk_ix_core_publication_keyword primary key (ix_core_publication_id, ix_core_value_id))
;

create table ix_core_publication_mesh (
  ix_core_publication_id         bigint not null,
  ix_core_value_id               bigint not null,
  constraint pk_ix_core_publication_mesh primary key (ix_core_publication_id, ix_core_value_id))
;

create table ix_core_publication_author (
  ix_core_publication_id         bigint not null,
  ix_core_pubauthor_id           bigint not null,
  constraint pk_ix_core_publication_author primary key (ix_core_publication_id, ix_core_pubauthor_id))
;

create table ix_core_publication_figure (
  ix_core_publication_id         bigint not null,
  ix_core_figure_id              bigint not null,
  constraint pk_ix_core_publication_figure primary key (ix_core_publication_id, ix_core_figure_id))
;

create table ix_core_resource_role (
  ix_core_resource_id            bigint not null,
  ix_core_role_id                bigint not null,
  constraint pk_ix_core_resource_role primary key (ix_core_resource_id, ix_core_role_id))
;

create table ix_core_resource_acl (
  ix_core_resource_id            bigint not null,
  ix_core_acl_id                 bigint not null,
  constraint pk_ix_core_resource_acl primary key (ix_core_resource_id, ix_core_acl_id))
;

create table ix_core_stitch_attribute (
  ix_core_stitch_id              bigint not null,
  ix_core_attribute_id           bigint not null,
  constraint pk_ix_core_stitch_attribute primary key (ix_core_stitch_id, ix_core_attribute_id))
;

create table ix_core_value_attribute (
  ix_core_value_id               bigint not null,
  ix_core_attribute_id           bigint not null,
  constraint pk_ix_core_value_attribute primary key (ix_core_value_id, ix_core_attribute_id))
;
create sequence ix_core_acl_seq;

create sequence ix_ncats_clinical_arm_seq;

create sequence ix_core_attribute_seq;

create sequence ix_ncats_clinical_trial_seq;

create sequence ix_ncats_clinical_cohort_seq;

create sequence ix_ncats_clinical_condition_seq;

create sequence ix_core_curation_seq;

create sequence ix_core_etag_seq;

create sequence ix_core_etagref_seq;

create sequence ix_core_edit_seq;

create sequence ix_ncats_clinical_eligibility_seq;

create sequence ix_idg_entity_seq;

create sequence ix_core_event_seq;

create sequence ix_core_figure_seq;

create sequence ix_ncats_funding_seq;

create sequence ix_core_gene_seq;

create sequence ix_ncats_grant_seq;

create sequence ix_core_group_seq;

create sequence ix_ncats_clinical_intervention_seq;

create sequence ix_core_investigator_seq;

create sequence ix_core_journal_seq;

create sequence ix_core_organization_seq;

create sequence ix_ncats_clinical_outcome_seq;

create sequence ix_core_payload_seq;

create sequence ix_core_predicate_seq;

create sequence ix_core_principal_seq;

create sequence ix_core_processingstatus_seq;

create sequence ix_ncats_project_seq;

create sequence ix_core_pubauthor_seq;

create sequence ix_core_publication_seq;

create sequence ix_core_resource_seq;

create sequence ix_core_role_seq;

create sequence ix_core_stitch_seq;

create sequence ix_core_value_seq;

create sequence ix_core_xref_seq;

alter table ix_core_attribute add constraint fk_ix_core_attribute_resource_1 foreign key (resource_id) references ix_core_resource (id) on delete restrict on update restrict;
create index ix_ix_core_attribute_resource_1 on ix_core_attribute (resource_id);
alter table ix_ncats_clinical_trial add constraint fk_ix_ncats_clinical_trial_eli_2 foreign key (eligibility_id) references ix_ncats_clinical_eligibility (id) on delete restrict on update restrict;
create index ix_ix_ncats_clinical_trial_eli_2 on ix_ncats_clinical_trial (eligibility_id);
alter table ix_core_curation add constraint fk_ix_core_curation_curator_3 foreign key (curator_id) references ix_core_principal (id) on delete restrict on update restrict;
create index ix_ix_core_curation_curator_3 on ix_core_curation (curator_id);
alter table ix_core_etagref add constraint fk_ix_core_etagref_etag_4 foreign key (etag_id) references ix_core_etag (id) on delete restrict on update restrict;
create index ix_ix_core_etagref_etag_4 on ix_core_etagref (etag_id);
alter table ix_core_edit add constraint fk_ix_core_edit_principal_5 foreign key (principal_id) references ix_core_principal (id) on delete restrict on update restrict;
create index ix_ix_core_edit_principal_5 on ix_core_edit (principal_id);
alter table ix_ncats_funding add constraint fk_ix_ncats_funding_ix_ncats_g_6 foreign key (grant_id) references ix_ncats_grant (id) on delete restrict on update restrict;
create index ix_ix_ncats_funding_ix_ncats_g_6 on ix_ncats_funding (grant_id);
alter table ix_core_investigator add constraint fk_ix_core_investigator_organi_7 foreign key (organization_id) references ix_core_organization (id) on delete restrict on update restrict;
create index ix_ix_core_investigator_organi_7 on ix_core_investigator (organization_id);
alter table ix_core_predicate add constraint fk_ix_core_predicate_object_8 foreign key (object_id) references ix_core_xref (id) on delete restrict on update restrict;
create index ix_ix_core_predicate_object_8 on ix_core_predicate (object_id);
alter table ix_core_principal add constraint fk_ix_core_principal_selfie_9 foreign key (selfie_id) references ix_core_figure (id) on delete restrict on update restrict;
create index ix_ix_core_principal_selfie_9 on ix_core_principal (selfie_id);
alter table ix_core_processingstatus add constraint fk_ix_core_processingstatus_p_10 foreign key (payload_id) references ix_core_payload (id) on delete restrict on update restrict;
create index ix_ix_core_processingstatus_p_10 on ix_core_processingstatus (payload_id);
alter table ix_ncats_project add constraint fk_ix_ncats_project_acl_11 foreign key (acl_id) references ix_core_acl (id) on delete restrict on update restrict;
create index ix_ix_ncats_project_acl_11 on ix_ncats_project (acl_id);
alter table ix_ncats_project add constraint fk_ix_ncats_project_curation_12 foreign key (curation_id) references ix_core_curation (id) on delete restrict on update restrict;
create index ix_ix_ncats_project_curation_12 on ix_ncats_project (curation_id);
alter table ix_core_pubauthor add constraint fk_ix_core_pubauthor_author_13 foreign key (author_id) references ix_core_principal (id) on delete restrict on update restrict;
create index ix_ix_core_pubauthor_author_13 on ix_core_pubauthor (author_id);
alter table ix_core_publication add constraint fk_ix_core_publication_journa_14 foreign key (journal_id) references ix_core_journal (id) on delete restrict on update restrict;
create index ix_ix_core_publication_journa_14 on ix_core_publication (journal_id);
alter table ix_core_role add constraint fk_ix_core_role_principal_15 foreign key (principal_id) references ix_core_principal (id) on delete restrict on update restrict;
create index ix_ix_core_role_principal_15 on ix_core_role (principal_id);
alter table ix_core_value add constraint fk_ix_core_value_curation_16 foreign key (curation_id) references ix_core_curation (id) on delete restrict on update restrict;
create index ix_ix_core_value_curation_16 on ix_core_value (curation_id);



alter table ix_core_acl_principal add constraint fk_ix_core_acl_principal_ix_c_01 foreign key (ix_core_acl_id) references ix_core_acl (id) on delete restrict on update restrict;

alter table ix_core_acl_principal add constraint fk_ix_core_acl_principal_ix_c_02 foreign key (ix_core_principal_id) references ix_core_principal (id) on delete restrict on update restrict;

alter table ix_core_acl_group add constraint fk_ix_core_acl_group_ix_core__01 foreign key (ix_core_acl_id) references ix_core_acl (id) on delete restrict on update restrict;

alter table ix_core_acl_group add constraint fk_ix_core_acl_group_ix_core__02 foreign key (ix_core_group_id) references ix_core_group (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_keyword add constraint fk_ix_ncats_clinical_trial_ke_01 foreign key (ix_ncats_clinical_trial_id) references ix_ncats_clinical_trial (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_keyword add constraint fk_ix_ncats_clinical_trial_ke_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_sponsor add constraint fk_ix_ncats_clinical_trial_sp_01 foreign key (ix_ncats_clinical_trial_id) references ix_ncats_clinical_trial (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_sponsor add constraint fk_ix_ncats_clinical_trial_sp_02 foreign key (ix_core_organization_id) references ix_core_organization (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_intervention add constraint fk_ix_ncats_clinical_trial_in_01 foreign key (ix_ncats_clinical_trial_id) references ix_ncats_clinical_trial (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_intervention add constraint fk_ix_ncats_clinical_trial_in_02 foreign key (ix_ncats_clinical_intervention_id) references ix_ncats_clinical_intervention (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_condition add constraint fk_ix_ncats_clinical_trial_co_01 foreign key (ix_ncats_clinical_trial_id) references ix_ncats_clinical_trial (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_condition add constraint fk_ix_ncats_clinical_trial_co_02 foreign key (ix_ncats_clinical_condition_id) references ix_ncats_clinical_condition (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_outcome add constraint fk_ix_ncats_clinical_trial_ou_01 foreign key (ix_ncats_clinical_trial_id) references ix_ncats_clinical_trial (id) on delete restrict on update restrict;

alter table ix_ncats_clinical_trial_outcome add constraint fk_ix_ncats_clinical_trial_ou_02 foreign key (ix_ncats_clinical_outcome_id) references ix_ncats_clinical_outcome (id) on delete restrict on update restrict;

alter table ix_ncats_clincial_trial_location add constraint fk_ix_ncats_clincial_trial_lo_01 foreign key (ix_ncats_clinical_trial_id) references ix_ncats_clinical_trial (id) on delete restrict on update restrict;

alter table ix_ncats_clincial_trial_location add constraint fk_ix_ncats_clincial_trial_lo_02 foreign key (ix_core_organization_id) references ix_core_organization (id) on delete restrict on update restrict;

alter table _ix_ncats_cca46885_1 add constraint fk__ix_ncats_cca46885_1_ix_nc_01 foreign key (ix_ncats_clinical_condition_synonym_id) references ix_ncats_clinical_condition (id) on delete restrict on update restrict;

alter table _ix_ncats_cca46885_1 add constraint fk__ix_ncats_cca46885_1_ix_co_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table _ix_ncats_cca46885_2 add constraint fk__ix_ncats_cca46885_2_ix_nc_01 foreign key (ix_ncats_clinical_condition_keyword_id) references ix_ncats_clinical_condition (id) on delete restrict on update restrict;

alter table _ix_ncats_cca46885_2 add constraint fk__ix_ncats_cca46885_2_ix_co_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table _ix_ncats_cca46885_3 add constraint fk__ix_ncats_cca46885_3_ix_nc_01 foreign key (ix_ncats_clinical_condition_wikipedia_id) references ix_ncats_clinical_condition (id) on delete restrict on update restrict;

alter table _ix_ncats_cca46885_3 add constraint fk__ix_ncats_cca46885_3_ix_co_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table _ix_ncats_840372f9_1 add constraint fk__ix_ncats_840372f9_1_ix_nc_01 foreign key (ix_ncats_clinical_eligibility_inclusion_id) references ix_ncats_clinical_eligibility (id) on delete restrict on update restrict;

alter table _ix_ncats_840372f9_1 add constraint fk__ix_ncats_840372f9_1_ix_co_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table _ix_ncats_840372f9_2 add constraint fk__ix_ncats_840372f9_2_ix_nc_01 foreign key (ix_ncats_clinical_eligibility_exclusion_id) references ix_ncats_clinical_eligibility (id) on delete restrict on update restrict;

alter table _ix_ncats_840372f9_2 add constraint fk__ix_ncats_840372f9_2_ix_co_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_idg_target_synonym add constraint fk_ix_idg_target_synonym_ix_i_01 foreign key (ix_idg_entity_id) references ix_idg_entity (id) on delete restrict on update restrict;

alter table ix_idg_target_synonym add constraint fk_ix_idg_target_synonym_ix_c_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_idg_target_annotation add constraint fk_ix_idg_target_annotation_i_01 foreign key (ix_idg_entity_id) references ix_idg_entity (id) on delete restrict on update restrict;

alter table ix_idg_target_annotation add constraint fk_ix_idg_target_annotation_i_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_idg_target_publication add constraint fk_ix_idg_target_publication__01 foreign key (ix_idg_entity_id) references ix_idg_entity (id) on delete restrict on update restrict;

alter table ix_idg_target_publication add constraint fk_ix_idg_target_publication__02 foreign key (ix_core_publication_id) references ix_core_publication (id) on delete restrict on update restrict;

alter table ix_idg_target_predicate add constraint fk_ix_idg_target_predicate_ix_01 foreign key (ix_idg_entity_id) references ix_idg_entity (id) on delete restrict on update restrict;

alter table ix_idg_target_predicate add constraint fk_ix_idg_target_predicate_ix_02 foreign key (ix_core_predicate_id) references ix_core_predicate (id) on delete restrict on update restrict;

alter table ix_core_event_figure add constraint fk_ix_core_event_figure_ix_co_01 foreign key (ix_core_event_id) references ix_core_event (id) on delete restrict on update restrict;

alter table ix_core_event_figure add constraint fk_ix_core_event_figure_ix_co_02 foreign key (ix_core_figure_id) references ix_core_figure (id) on delete restrict on update restrict;

alter table ix_core_gene_synonym add constraint fk_ix_core_gene_synonym_ix_co_01 foreign key (ix_core_gene_id) references ix_core_gene (id) on delete restrict on update restrict;

alter table ix_core_gene_synonym add constraint fk_ix_core_gene_synonym_ix_co_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_ncats_grant_investigator add constraint fk_ix_ncats_grant_investigato_01 foreign key (ix_ncats_grant_id) references ix_ncats_grant (id) on delete restrict on update restrict;

alter table ix_ncats_grant_investigator add constraint fk_ix_ncats_grant_investigato_02 foreign key (ix_core_investigator_id) references ix_core_investigator (id) on delete restrict on update restrict;

alter table ix_ncats_grant_keyword add constraint fk_ix_ncats_grant_keyword_ix__01 foreign key (ix_ncats_grant_id) references ix_ncats_grant (id) on delete restrict on update restrict;

alter table ix_ncats_grant_keyword add constraint fk_ix_ncats_grant_keyword_ix__02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_ncats_grant_publication add constraint fk_ix_ncats_grant_publication_01 foreign key (ix_ncats_grant_id) references ix_ncats_grant (id) on delete restrict on update restrict;

alter table ix_ncats_grant_publication add constraint fk_ix_ncats_grant_publication_02 foreign key (ix_core_publication_id) references ix_core_publication (id) on delete restrict on update restrict;

alter table ix_core_group_principal add constraint fk_ix_core_group_principal_ix_01 foreign key (ix_core_group_id) references ix_core_group (id) on delete restrict on update restrict;

alter table ix_core_group_principal add constraint fk_ix_core_group_principal_ix_02 foreign key (ix_core_principal_id) references ix_core_principal (id) on delete restrict on update restrict;

alter table _ix_ncats_4a162ae3_1 add constraint fk__ix_ncats_4a162ae3_1_ix_nc_01 foreign key (ix_ncats_clinical_intervention_id) references ix_ncats_clinical_intervention (id) on delete restrict on update restrict;

alter table _ix_ncats_4a162ae3_1 add constraint fk__ix_ncats_4a162ae3_1_ix_co_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table _ix_ncats_4a162ae3_2 add constraint fk__ix_ncats_4a162ae3_2_ix_nc_01 foreign key (ix_ncats_clinical_intervention_id) references ix_ncats_clinical_intervention (id) on delete restrict on update restrict;

alter table _ix_ncats_4a162ae3_2 add constraint fk__ix_ncats_4a162ae3_2_ix_nc_02 foreign key (ix_ncats_clinical_arm_id) references ix_ncats_clinical_arm (id) on delete restrict on update restrict;

alter table _ix_ncats_4a162ae3_3 add constraint fk__ix_ncats_4a162ae3_3_ix_nc_01 foreign key (ix_ncats_clinical_intervention_id) references ix_ncats_clinical_intervention (id) on delete restrict on update restrict;

alter table _ix_ncats_4a162ae3_3 add constraint fk__ix_ncats_4a162ae3_3_ix_nc_02 foreign key (ix_ncats_clinical_cohort_id) references ix_ncats_clinical_cohort (id) on delete restrict on update restrict;

alter table ix_core_payload_attribute add constraint fk_ix_core_payload_attribute__01 foreign key (ix_core_payload_id) references ix_core_payload (id) on delete restrict on update restrict;

alter table ix_core_payload_attribute add constraint fk_ix_core_payload_attribute__02 foreign key (ix_core_attribute_id) references ix_core_attribute (id) on delete restrict on update restrict;

alter table ix_core_predicate_annotation add constraint fk_ix_core_predicate_annotati_01 foreign key (ix_core_predicate_id) references ix_core_predicate (id) on delete restrict on update restrict;

alter table ix_core_predicate_annotation add constraint fk_ix_core_predicate_annotati_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_ncats_project_annotation add constraint fk_ix_ncats_project_annotatio_01 foreign key (ix_ncats_project_id) references ix_ncats_project (id) on delete restrict on update restrict;

alter table ix_ncats_project_annotation add constraint fk_ix_ncats_project_annotatio_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_ncats_project_member add constraint fk_ix_ncats_project_member_ix_01 foreign key (ix_ncats_project_id) references ix_ncats_project (id) on delete restrict on update restrict;

alter table ix_ncats_project_member add constraint fk_ix_ncats_project_member_ix_02 foreign key (ix_core_principal_id) references ix_core_principal (id) on delete restrict on update restrict;

alter table ix_ncats_project_collaborator add constraint fk_ix_ncats_project_collabora_01 foreign key (ix_ncats_project_id) references ix_ncats_project (id) on delete restrict on update restrict;

alter table ix_ncats_project_collaborator add constraint fk_ix_ncats_project_collabora_02 foreign key (ix_core_principal_id) references ix_core_principal (id) on delete restrict on update restrict;

alter table ix_ncats_project_figure add constraint fk_ix_ncats_project_figure_ix_01 foreign key (ix_ncats_project_id) references ix_ncats_project (id) on delete restrict on update restrict;

alter table ix_ncats_project_figure add constraint fk_ix_ncats_project_figure_ix_02 foreign key (ix_core_figure_id) references ix_core_figure (id) on delete restrict on update restrict;

alter table ix_ncats_project_milestone add constraint fk_ix_ncats_project_milestone_01 foreign key (ix_ncats_project_id) references ix_ncats_project (id) on delete restrict on update restrict;

alter table ix_ncats_project_milestone add constraint fk_ix_ncats_project_milestone_02 foreign key (ix_core_event_id) references ix_core_event (id) on delete restrict on update restrict;

alter table ix_ncats_project_publication add constraint fk_ix_ncats_project_publicati_01 foreign key (ix_ncats_project_id) references ix_ncats_project (id) on delete restrict on update restrict;

alter table ix_ncats_project_publication add constraint fk_ix_ncats_project_publicati_02 foreign key (ix_core_publication_id) references ix_core_publication (id) on delete restrict on update restrict;

alter table ix_core_publication_keyword add constraint fk_ix_core_publication_keywor_01 foreign key (ix_core_publication_id) references ix_core_publication (id) on delete restrict on update restrict;

alter table ix_core_publication_keyword add constraint fk_ix_core_publication_keywor_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_core_publication_mesh add constraint fk_ix_core_publication_mesh_i_01 foreign key (ix_core_publication_id) references ix_core_publication (id) on delete restrict on update restrict;

alter table ix_core_publication_mesh add constraint fk_ix_core_publication_mesh_i_02 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_core_publication_author add constraint fk_ix_core_publication_author_01 foreign key (ix_core_publication_id) references ix_core_publication (id) on delete restrict on update restrict;

alter table ix_core_publication_author add constraint fk_ix_core_publication_author_02 foreign key (ix_core_pubauthor_id) references ix_core_pubauthor (id) on delete restrict on update restrict;

alter table ix_core_publication_figure add constraint fk_ix_core_publication_figure_01 foreign key (ix_core_publication_id) references ix_core_publication (id) on delete restrict on update restrict;

alter table ix_core_publication_figure add constraint fk_ix_core_publication_figure_02 foreign key (ix_core_figure_id) references ix_core_figure (id) on delete restrict on update restrict;

alter table ix_core_resource_role add constraint fk_ix_core_resource_role_ix_c_01 foreign key (ix_core_resource_id) references ix_core_resource (id) on delete restrict on update restrict;

alter table ix_core_resource_role add constraint fk_ix_core_resource_role_ix_c_02 foreign key (ix_core_role_id) references ix_core_role (id) on delete restrict on update restrict;

alter table ix_core_resource_acl add constraint fk_ix_core_resource_acl_ix_co_01 foreign key (ix_core_resource_id) references ix_core_resource (id) on delete restrict on update restrict;

alter table ix_core_resource_acl add constraint fk_ix_core_resource_acl_ix_co_02 foreign key (ix_core_acl_id) references ix_core_acl (id) on delete restrict on update restrict;

alter table ix_core_stitch_attribute add constraint fk_ix_core_stitch_attribute_i_01 foreign key (ix_core_stitch_id) references ix_core_stitch (id) on delete restrict on update restrict;

alter table ix_core_stitch_attribute add constraint fk_ix_core_stitch_attribute_i_02 foreign key (ix_core_attribute_id) references ix_core_attribute (id) on delete restrict on update restrict;

alter table ix_core_value_attribute add constraint fk_ix_core_value_attribute_ix_01 foreign key (ix_core_value_id) references ix_core_value (id) on delete restrict on update restrict;

alter table ix_core_value_attribute add constraint fk_ix_core_value_attribute_ix_02 foreign key (ix_core_attribute_id) references ix_core_attribute (id) on delete restrict on update restrict;

# --- !Downs

SET REFERENTIAL_INTEGRITY FALSE;

drop table if exists ix_core_acl;

drop table if exists ix_core_acl_principal;

drop table if exists ix_core_acl_group;

drop table if exists ix_ncats_clinical_arm;

drop table if exists ix_core_attribute;

drop table if exists ix_ncats_clinical_trial;

drop table if exists ix_ncats_clinical_trial_keyword;

drop table if exists ix_ncats_clinical_trial_sponsor;

drop table if exists ix_ncats_clinical_trial_intervention;

drop table if exists ix_ncats_clinical_trial_condition;

drop table if exists ix_ncats_clinical_trial_outcome;

drop table if exists ix_ncats_clincial_trial_location;

drop table if exists ix_ncats_clinical_cohort;

drop table if exists ix_ncats_clinical_condition;

drop table if exists _ix_ncats_cca46885_1;

drop table if exists _ix_ncats_cca46885_2;

drop table if exists _ix_ncats_cca46885_3;

drop table if exists ix_core_curation;

drop table if exists ix_core_etag;

drop table if exists ix_core_etagref;

drop table if exists ix_core_edit;

drop table if exists ix_ncats_clinical_eligibility;

drop table if exists _ix_ncats_840372f9_1;

drop table if exists _ix_ncats_840372f9_2;

drop table if exists ix_idg_entity;

drop table if exists ix_idg_target_synonym;

drop table if exists ix_idg_target_annotation;

drop table if exists ix_idg_target_publication;

drop table if exists ix_idg_target_predicate;

drop table if exists ix_core_event;

drop table if exists ix_core_event_figure;

drop table if exists ix_core_figure;

drop table if exists ix_ncats_funding;

drop table if exists ix_core_gene;

drop table if exists ix_core_gene_synonym;

drop table if exists ix_ncats_grant;

drop table if exists ix_ncats_grant_investigator;

drop table if exists ix_ncats_grant_keyword;

drop table if exists ix_ncats_grant_publication;

drop table if exists ix_core_group;

drop table if exists ix_core_group_principal;

drop table if exists ix_ncats_clinical_intervention;

drop table if exists _ix_ncats_4a162ae3_1;

drop table if exists _ix_ncats_4a162ae3_2;

drop table if exists _ix_ncats_4a162ae3_3;

drop table if exists ix_core_investigator;

drop table if exists ix_core_journal;

drop table if exists ix_core_organization;

drop table if exists ix_ncats_clinical_outcome;

drop table if exists ix_core_payload;

drop table if exists ix_core_payload_attribute;

drop table if exists ix_core_predicate;

drop table if exists ix_core_predicate_annotation;

drop table if exists ix_core_principal;

drop table if exists ix_core_processingstatus;

drop table if exists ix_ncats_project;

drop table if exists ix_ncats_project_annotation;

drop table if exists ix_ncats_project_member;

drop table if exists ix_ncats_project_collaborator;

drop table if exists ix_ncats_project_figure;

drop table if exists ix_ncats_project_milestone;

drop table if exists ix_ncats_project_publication;

drop table if exists ix_core_pubauthor;

drop table if exists ix_core_publication;

drop table if exists ix_core_publication_keyword;

drop table if exists ix_core_publication_mesh;

drop table if exists ix_core_publication_author;

drop table if exists ix_core_publication_figure;

drop table if exists ix_core_resource;

drop table if exists ix_core_resource_role;

drop table if exists ix_core_resource_acl;

drop table if exists ix_core_role;

drop table if exists ix_core_stitch;

drop table if exists ix_core_stitch_attribute;

drop table if exists ix_core_value;

drop table if exists ix_core_value_attribute;

drop table if exists ix_core_xref;

SET REFERENTIAL_INTEGRITY TRUE;

drop sequence if exists ix_core_acl_seq;

drop sequence if exists ix_ncats_clinical_arm_seq;

drop sequence if exists ix_core_attribute_seq;

drop sequence if exists ix_ncats_clinical_trial_seq;

drop sequence if exists ix_ncats_clinical_cohort_seq;

drop sequence if exists ix_ncats_clinical_condition_seq;

drop sequence if exists ix_core_curation_seq;

drop sequence if exists ix_core_etag_seq;

drop sequence if exists ix_core_etagref_seq;

drop sequence if exists ix_core_edit_seq;

drop sequence if exists ix_ncats_clinical_eligibility_seq;

drop sequence if exists ix_idg_entity_seq;

drop sequence if exists ix_core_event_seq;

drop sequence if exists ix_core_figure_seq;

drop sequence if exists ix_ncats_funding_seq;

drop sequence if exists ix_core_gene_seq;

drop sequence if exists ix_ncats_grant_seq;

drop sequence if exists ix_core_group_seq;

drop sequence if exists ix_ncats_clinical_intervention_seq;

drop sequence if exists ix_core_investigator_seq;

drop sequence if exists ix_core_journal_seq;

drop sequence if exists ix_core_organization_seq;

drop sequence if exists ix_ncats_clinical_outcome_seq;

drop sequence if exists ix_core_payload_seq;

drop sequence if exists ix_core_predicate_seq;

drop sequence if exists ix_core_principal_seq;

drop sequence if exists ix_core_processingstatus_seq;

drop sequence if exists ix_ncats_project_seq;

drop sequence if exists ix_core_pubauthor_seq;

drop sequence if exists ix_core_publication_seq;

drop sequence if exists ix_core_resource_seq;

drop sequence if exists ix_core_role_seq;

drop sequence if exists ix_core_stitch_seq;

drop sequence if exists ix_core_value_seq;

drop sequence if exists ix_core_xref_seq;

