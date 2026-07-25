-- V18: pin the counterparty-name normalization rule in the database itself.
--
-- Tasks 1-5 gave the rule a single shared source (substrate/NameNormalization) and routed every
-- Java write path through it, so every value written today is a fixpoint of the formula by
-- construction. That is a property of the current code, not of the data: a future write path, a
-- restored dump or a manual psql session can still deposit a padded or lower-cased name and
-- silently re-fragment counterparty identity. These CHECKs make that impossible at the only layer
-- every writer must pass through.
--
-- The rule (frozen, verbatim from NameNormalization):
--   display  = trim(regexp_replace(normalize(x, NFC), '\s+', ' ', 'g'))
--   identity = upper(display)
--
-- Three phases: preflight (diagnose collisions before touching anything), backfill (defensive,
-- zero rows on the live database), constraints.

-- ---------------------------------------------------------------------------------------------
-- Phase 1: preflight
--
-- Normalizing an identity_value can push it onto a value another row already holds, which would
-- surface as a bare unique-constraint violation somewhere inside phase 2 -- undiagnosable after
-- the fact, since the offending value no longer exists in the form that caused it. Detect every
-- such constellation first and abort with the concrete ids.
--
-- Numbering legend: seven shapes behind six guards. Case 3 folds two of them into one guard via
-- UNION ALL (alias-vs-alias in both directions), and "case 5" is not a guard at all -- it names
-- the permitted exemption carved out inside case 4's WHERE clause. The guards below are therefore
-- labelled 1, 2, 3, 4, 6, 7.
--
-- All seven constellations have zero occurrences on the live database (verified 2026-07-25: 910
-- counterparties, 0 dirty; 2 tombstones, both clean; 2 alias rows, both clean). This block is
-- protection for an older or foreign copy of the schema, not a live concern.
-- ---------------------------------------------------------------------------------------------
DO
$$
    DECLARE
        offenders text;
    BEGIN

        -- Case 1: two live counterparties that are both dirty and normalize onto each other.
        -- Neither holds the target value yet, so this is invisible to a per-row lookup.
        SELECT string_agg(msg, '; ' ORDER BY msg)
        INTO offenders
        FROM (SELECT format('counterparties %s and %s both normalize to %L',
                            a.id, b.id,
                            upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ',
                                                      'g')))) AS msg
              FROM counterparties a
                       JOIN counterparties b
                            ON b.id > a.id
                                AND b.identity_type = 'name'
                                AND b.merged_into IS NULL
                                AND upper(trim(regexp_replace(normalize(b.identity_value, NFC),
                                                              '\s+', ' ', 'g'))) =
                                    upper(trim(regexp_replace(normalize(a.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
                                AND b.identity_value <>
                                    upper(trim(regexp_replace(normalize(b.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
              WHERE a.identity_type = 'name'
                AND a.merged_into IS NULL
                AND a.identity_value <>
                    upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ',
                                              'g')))) t;
        IF offenders IS NOT NULL THEN
            RAISE EXCEPTION 'V18 preflight: normalizing name identities would collide: %', offenders
                USING HINT = 'Fold the listed counterparties with merge_counterparty, then re-run '
                    'the migration. The tombstone left behind keeps its unmodified identity_value '
                    'and is exempt from this check.';
        END IF;

        -- Case 2: a dirty live counterparty normalizing onto a value some other row already holds.
        -- The holder is deliberately unrestricted: a clean live row and a merge tombstone occupy
        -- the uq_counterparty_identity slot alike, so both are genuine collision partners even
        -- though only the live one is semantically meaningful.
        SELECT string_agg(msg, '; ' ORDER BY msg)
        INTO offenders
        -- The holder's merged_into is emitted when it is a tombstone, because that changes the
        -- remedy: merge_counterparty rejects folded ids, so the operator has to aim at the
        -- tombstone's own merge target instead. Making them derive that from a bare id would be
        -- cruel to someone whose deploy has just stopped.
        FROM (SELECT format('counterparty %s normalizes to %L, already held by counterparty %s%s',
                            a.id,
                            upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ',
                                                      'g'))),
                            b.id,
                            CASE
                                WHEN b.merged_into IS NOT NULL
                                    THEN format(' (a merge tombstone folded into %s)',
                                                b.merged_into)
                                ELSE '' END) AS msg
              FROM counterparties a
                       JOIN counterparties b
                            ON b.id <> a.id
                                AND b.identity_type = 'name'
                                AND b.identity_value =
                                    upper(trim(regexp_replace(normalize(a.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
              WHERE a.identity_type = 'name'
                AND a.merged_into IS NULL
                AND a.identity_value <>
                    upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ',
                                              'g')))) t;
        IF offenders IS NOT NULL THEN
            RAISE EXCEPTION 'V18 preflight: normalizing name identities would collide: %', offenders
                USING HINT = 'Fold the listed counterparties with merge_counterparty, then re-run '
                    'the migration. Where the holder is reported as a merge tombstone, aim the '
                    'merge at the target it was folded into -- merge_counterparty rejects a '
                    'folded id as its own target.';
        END IF;

        -- Case 3: the same two shapes inside counterparty_alias, which carries its own
        -- uq_counterparty_alias UNIQUE (identity_type, identity_value). Aliases have no
        -- merged_into equivalent, so every alias row is in scope.
        SELECT string_agg(msg, '; ' ORDER BY msg)
        INTO offenders
        FROM (SELECT format('aliases %s and %s both normalize to %L',
                            a.id, b.id,
                            upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ',
                                                      'g')))) AS msg
              FROM counterparty_alias a
                       JOIN counterparty_alias b
                            ON b.id > a.id
                                AND b.identity_type = 'name'
                                AND upper(trim(regexp_replace(normalize(b.identity_value, NFC),
                                                              '\s+', ' ', 'g'))) =
                                    upper(trim(regexp_replace(normalize(a.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
                                AND b.identity_value <>
                                    upper(trim(regexp_replace(normalize(b.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
              WHERE a.identity_type = 'name'
                AND a.identity_value <>
                    upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ', 'g')))
              UNION ALL
              SELECT format('alias %s normalizes to %L, already held by alias %s',
                            a.id,
                            upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ',
                                                      'g'))),
                            b.id) AS msg
              FROM counterparty_alias a
                       JOIN counterparty_alias b
                            ON b.id <> a.id
                                AND b.identity_type = 'name'
                                AND b.identity_value =
                                    upper(trim(regexp_replace(normalize(a.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
              WHERE a.identity_type = 'name'
                AND a.identity_value <>
                    upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ',
                                              'g')))) t;
        IF offenders IS NOT NULL THEN
            RAISE EXCEPTION 'V18 preflight: normalizing alias identities would collide: %', offenders
                -- Deletion is the only remedy: this collision is on
                -- (identity_type, identity_value), which canonical_counterparty_id does not
                -- participate in, so repointing an alias leaves the guard firing identically.
                USING HINT = 'Delete the redundant counterparty_alias rows, then re-run the '
                    'migration. Repointing them does not help -- the collision is on '
                    '(identity_type, identity_value), which the canonical target is not part of.';
        END IF;

        -- Case 4: a dirty alias whose normalized value is the identity of a *live* counterparty
        -- it does not point at. No unique constraint is violated (different tables), which is
        -- exactly why this has to be caught here: resolution reads
        -- COALESCE(alias.canonical_counterparty_id, own.id), so after normalization that identity
        -- would silently stop resolving to its own physical row and start resolving to the alias's
        -- canonical target instead. A migration must not re-point live data.
        --
        -- THE EXEMPTION (case 5): the alias already points at the row holding the normalized
        -- value. merge_counterparty copies the folded source's identity_value verbatim into the
        -- alias row, so a dirty tombstone always comes with an alias carrying the same dirty
        -- value -- and normalizing that alias necessarily lands it on the merge target's identity.
        -- Excluding it is not a loosening: COALESCE(alias.canonical, own.id) yields the same id
        -- before and after. Without the exclusion this preflight would abort on precisely the
        -- configuration the tombstone exemption exists to permit, and the remediation prescribed
        -- for cases 1-3 (merge, then re-run) would dead-end in a permanent abort.
        SELECT string_agg(msg, '; ' ORDER BY msg)
        INTO offenders
        FROM (SELECT format('alias %s (canonical %s) normalizes to %L, the identity of live '
                            'counterparty %s -- that identity would be diverted away from its own '
                            'row', a.id, a.canonical_counterparty_id,
                            upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ',
                                                      'g'))),
                            c.id) AS msg
              FROM counterparty_alias a
                       JOIN counterparties c
                            ON c.identity_type = 'name'
                                AND c.merged_into IS NULL
                                AND c.identity_value =
                                    upper(trim(regexp_replace(normalize(a.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
                                AND c.id <> a.canonical_counterparty_id
              WHERE a.identity_type = 'name'
                AND a.identity_value <>
                    upper(trim(regexp_replace(normalize(a.identity_value, NFC), '\s+', ' ',
                                              'g')))) t;
        IF offenders IS NOT NULL THEN
            RAISE EXCEPTION 'V18 preflight: normalizing alias identities would divert resolution: %',
                offenders
                USING HINT = 'Delete the alias row, or merge the live counterparty into the '
                    'alias target so the two agree, then re-run the migration.';
        END IF;

        -- Case 6: the mirror image of case 4. A dirty *counterparty* whose normalized identity is
        -- already held by a *clean alias row* pointing somewhere else. Again no unique constraint
        -- fires -- the two rows live in different tables -- but the harm is identical and arrives
        -- from the other side: once the backfill normalizes the counterparty, every booking that
        -- resolves to that identity pools onto the alias's canonical target instead of onto the
        -- counterparty's own row, because resolution reads
        -- COALESCE(alias.canonical_counterparty_id, own.id) and the alias wins.
        --
        -- Restricted to merged_into IS NULL for the same reason as the backfill: a tombstone's
        -- identity is never rewritten, so it cannot move onto an alias.
        --
        -- The exclusion mirrors case 5's: if the alias already points at this very counterparty,
        -- COALESCE yields the same id before and after, so nothing is diverted and the
        -- configuration must pass.
        --
        -- Ordering: this runs last and therefore cannot mask any earlier guard. It also cannot be
        -- masked by one -- cases 1/2 look at counterparty-vs-counterparty collisions and cases 3/4
        -- require a *dirty* alias, whereas this one is about a clean-or-dirty alias colliding with
        -- a dirty counterparty. Where an input satisfies both this and an earlier guard, the
        -- earlier one (a genuine unique violation) is the more urgent diagnosis anyway.
        SELECT string_agg(msg, '; ' ORDER BY msg)
        INTO offenders
        FROM (SELECT format('counterparty %s normalizes to %L, already held by alias %s '
                            '(canonical %s) -- that identity would be diverted away from the '
                            'counterparty''s own row', c.id,
                            upper(trim(regexp_replace(normalize(c.identity_value, NFC), '\s+', ' ',
                                                      'g'))),
                            a.id, a.canonical_counterparty_id) AS msg
              FROM counterparties c
                       JOIN counterparty_alias a
                            ON a.identity_type = 'name'
                                AND a.identity_value =
                                    upper(trim(regexp_replace(normalize(c.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
                                AND a.canonical_counterparty_id <> c.id
              WHERE c.identity_type = 'name'
                AND c.merged_into IS NULL
                AND c.identity_value <>
                    upper(trim(regexp_replace(normalize(c.identity_value, NFC), '\s+', ' ',
                                              'g')))) t;
        IF offenders IS NOT NULL THEN
            RAISE EXCEPTION 'V18 preflight: normalizing name identities would divert resolution: %',
                offenders
                USING HINT = 'Delete or repoint the alias row, or merge the counterparty into the '
                    'alias target so the two agree, then re-run the migration.';
        END IF;

        -- Case 7: a counterparty and an alias that are BOTH dirty and converge only once the
        -- backfill has run. Cases 4 and 6 each compare one side's normalized value against the
        -- other side's *current* value, which is exactly right when one side is already clean --
        -- but when both are dirty neither join matches: the counterparty still holds Y', the alias
        -- still holds X', and they only become the same X in phase 2. The harm on arrival is the
        -- one cases 4 and 6 exist to prevent -- resolution reads
        -- COALESCE(alias.canonical_counterparty_id, own.id), so the counterparty's identity is
        -- diverted to the alias's canonical target -- and again no unique constraint fires,
        -- because the two rows live in different tables.
        --
        -- This guard therefore compares normalized against normalized on both sides.
        --
        -- Masking: this case is *provably disjoint* from cases 4 and 6 rather than merely ordered
        -- after them. The normal form is idempotent, so normalized(x) is always a fixpoint. Case 4
        -- requires c.identity_value = normalized(a.identity_value), which makes c.identity_value a
        -- fixpoint, i.e. c is clean; case 6 symmetrically forces the alias clean. Case 7 requires
        -- both dirty. No input can satisfy case 7 and either of them, so neither ordering nor
        -- masking is at issue between the three. Against cases 1-3 the usual rule applies: where
        -- an input satisfies both, the earlier guard fires first, and a genuine unique violation
        -- is the more urgent diagnosis.
        --
        -- Cannot fire on the live database: it needs a dirty counterparty and a dirty alias
        -- simultaneously, and production has zero dirty counterparties and two clean alias rows.
        -- Protection for an older or foreign schema, like the rest of this block.
        SELECT string_agg(msg, '; ' ORDER BY msg)
        INTO offenders
        FROM (SELECT format('counterparty %s and alias %s (canonical %s) both normalize to %L -- '
                            'that identity would be diverted away from the counterparty''s own '
                            'row', c.id, a.id, a.canonical_counterparty_id,
                            upper(trim(regexp_replace(normalize(c.identity_value, NFC), '\s+', ' ',
                                                      'g')))) AS msg
              FROM counterparties c
                       JOIN counterparty_alias a
                            ON a.identity_type = 'name'
                                AND upper(trim(regexp_replace(normalize(a.identity_value, NFC),
                                                              '\s+', ' ', 'g'))) =
                                    upper(trim(regexp_replace(normalize(c.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
                                AND a.identity_value <>
                                    upper(trim(regexp_replace(normalize(a.identity_value, NFC),
                                                              '\s+', ' ', 'g')))
                                -- Same exclusion as cases 4 and 6: if the alias already points at
                                -- this counterparty, COALESCE yields the same id before and after,
                                -- so nothing is diverted.
                                AND a.canonical_counterparty_id <> c.id
              WHERE c.identity_type = 'name'
                AND c.merged_into IS NULL
                AND c.identity_value <>
                    upper(trim(regexp_replace(normalize(c.identity_value, NFC), '\s+', ' ',
                                              'g')))) t;
        IF offenders IS NOT NULL THEN
            RAISE EXCEPTION 'V18 preflight: normalizing name identities would divert resolution: %',
                offenders
                USING HINT = 'Delete or repoint the alias row, or merge the counterparty into the '
                    'alias target so the two agree, then re-run the migration.';
        END IF;

    END
$$;

-- ---------------------------------------------------------------------------------------------
-- Phase 2: defensive backfill, following V7's idiom (touch only rows that are not already a
-- fixpoint). On the live database all four statements hit zero rows -- they exist so the
-- constraints below do not hard-fail on an older or foreign copy of this schema.
-- ---------------------------------------------------------------------------------------------
UPDATE counterparties
SET display_name = trim(regexp_replace(normalize(display_name, NFC), '\s+', ' ', 'g'))
WHERE display_name IS NOT NULL
  AND display_name <> trim(regexp_replace(normalize(display_name, NFC), '\s+', ' ', 'g'));

UPDATE counterparties
SET display_name_override =
        trim(regexp_replace(normalize(display_name_override, NFC), '\s+', ' ', 'g'))
WHERE display_name_override IS NOT NULL
  AND display_name_override <>
      trim(regexp_replace(normalize(display_name_override, NFC), '\s+', ' ', 'g'));

-- Tombstones (merged_into IS NOT NULL) are deliberately skipped. Their identity is inert:
-- resolution for a folded identity goes through counterparty_alias and the read layer filters
-- merged_into IS NULL. All a tombstone still does is hold its slot in uq_counterparty_identity so
-- a re-import of the folded variant cannot recreate the row -- which is exactly what it should do,
-- and which requires the value to stay byte-identical to what the export produces.
UPDATE counterparties
SET identity_value =
        upper(trim(regexp_replace(normalize(identity_value, NFC), '\s+', ' ', 'g')))
WHERE identity_type = 'name'
  AND merged_into IS NULL
  AND identity_value <>
      upper(trim(regexp_replace(normalize(identity_value, NFC), '\s+', ' ', 'g')));

UPDATE counterparty_alias
SET identity_value =
        upper(trim(regexp_replace(normalize(identity_value, NFC), '\s+', ' ', 'g')))
WHERE identity_type = 'name'
  AND identity_value <>
      upper(trim(regexp_replace(normalize(identity_value, NFC), '\s+', ' ', 'g')));

-- ---------------------------------------------------------------------------------------------
-- Phase 3: the constraints.
--
-- Scope decisions, all deliberate:
--
--  * The identity constraints cover identity_type = 'name' only. For 'creditor_id' and 'iban' the
--    resolver passes the export's raw value straight through; that these happen to be uppercase
--    today is the bank's doing, not the code's. An unconditional constraint would promote a future
--    lower-cased IBAN in an export from a cosmetic quirk to a hard ingest abort.
--  * Tombstones are exempt from the identity constraint, matching the backfill above.
--  * The empty string stays legal: '' = upper(display('')) holds, so it passes. Names that
--    normalize away to nothing are a substrate concern handled in the resolver, not something to
--    smuggle into a normalization constraint.
--  * The display constraints DO apply to tombstones. display_name carries no unique constraint,
--    so normalizing it can never collide with anything.
--  * NULL columns pass: the comparison yields NULL, and a CHECK only fails on FALSE.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE counterparties
    ADD CONSTRAINT counterparties_display_name_normalized
        CHECK (display_name = trim(regexp_replace(normalize(display_name, NFC), '\s+', ' ', 'g'))),
    ADD CONSTRAINT counterparties_display_name_override_normalized
        CHECK (display_name_override =
               trim(regexp_replace(normalize(display_name_override, NFC), '\s+', ' ', 'g'))),
    ADD CONSTRAINT counterparties_name_identity_normalized
        CHECK (merged_into IS NOT NULL
            OR identity_type <> 'name'
            OR identity_value =
               upper(trim(regexp_replace(normalize(identity_value, NFC), '\s+', ' ', 'g'))));

ALTER TABLE counterparty_alias
    ADD CONSTRAINT counterparty_alias_name_identity_normalized
        CHECK (identity_type <> 'name'
            OR identity_value =
               upper(trim(regexp_replace(normalize(identity_value, NFC), '\s+', ' ', 'g'))));
