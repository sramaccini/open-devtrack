-- The ODQL filter of a board (ADR-16 point 7): only the issues it finds are its cards; none, every issue.
ALTER TABLE odt_agile.board ADD COLUMN filter varchar(2000);
