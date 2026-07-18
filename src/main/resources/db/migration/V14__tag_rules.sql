-- #37 auto-tagging rules: persistent Outlook-style condition->tag rules.
-- conditions/actions are validated JSON arrays; element-level validation is enforced in Java
-- (TagRuleValidator). The DB CHECKs are the array-shape / non-empty backstop only.
CREATE TABLE tag_rules (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT NOT NULL CHECK (btrim(name) <> ''),
    enabled    BOOLEAN NOT NULL DEFAULT true,
    conditions JSONB NOT NULL,
    actions    JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_tag_rules_conditions_nonempty
        CHECK (jsonb_typeof(conditions) = 'array' AND jsonb_array_length(conditions) >= 1),
    CONSTRAINT chk_tag_rules_actions_nonempty
        CHECK (jsonb_typeof(actions) = 'array' AND jsonb_array_length(actions) >= 1)
);
