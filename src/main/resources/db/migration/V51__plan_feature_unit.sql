ALTER TABLE subscription.plan_features
    ADD COLUMN unit varchar(10) NOT NULL DEFAULT 'NEW';

ALTER TABLE subscription.plan_features
    ADD CONSTRAINT ck_plan_features_unit CHECK (unit IN ('NEW', 'COUNT'));
