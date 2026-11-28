-- Time tracking (ADR-30), in whole minutes: the original and the remaining estimate, set by the users (optional), and
-- the time spent, the sum of the work logs of the issue, kept by the module collaboration as they change.
ALTER TABLE odt_issue.issue ADD COLUMN original_estimate integer;
ALTER TABLE odt_issue.issue ADD COLUMN remaining_estimate integer;
ALTER TABLE odt_issue.issue ADD COLUMN time_spent integer NOT NULL DEFAULT 0;
