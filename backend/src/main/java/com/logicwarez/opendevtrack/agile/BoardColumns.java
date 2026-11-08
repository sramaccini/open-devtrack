/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import com.logicwarez.opendevtrack.agile.BoardEntity.BoardColumn;
import com.logicwarez.opendevtrack.workflow.Workflows.Status;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The columns of a board as the dialog of the screens writes them: one per line, {@code Name: status, status | limit},
 * for example {@code Doing: in_progress, review | 3}. Each status of the workflow is in one column at most; a status in
 * none is shown in a column of its category ({@link #place}, ADR-20). An empty text: one column per status.
 */
final class BoardColumns {

    /** The most columns of the kanban of Quadrava. */
    static final int MAX = 10;

    private static final Pattern LINE = Pattern.compile("^([^:|]{1,100}):([^|]*)(?:\\|\\s*([0-9]{1,3}))?$");

    private BoardColumns() {
    }

    static final class Invalid extends Exception {
        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    static List<BoardColumn> parse(String text, List<String> statuses) throws Invalid {
        List<BoardColumn> columns = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return columns;
        }
        Set<String> used = new HashSet<>();
        for (String raw : text.strip().split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches() || m.group(1).isBlank()) {
                throw new Invalid("A column is a line «Name: status, status | limit»");
            }
            List<String> in = new ArrayList<>();
            for (String s : m.group(2).split(",")) {
                String status = s.strip();
                if (status.isEmpty()) {
                    continue;
                }
                if (!statuses.contains(status)) {
                    throw new Invalid("No status " + status + " in the workflow of the project");
                }
                if (!used.add(status)) {
                    throw new Invalid("The status " + status + " is in two columns");
                }
                in.add(status);
            }
            if (in.isEmpty()) {
                throw new Invalid("Each column holds at least one status");
            }
            Integer limit = m.group(3) == null ? null : Integer.valueOf(m.group(3));
            if (limit != null && limit < 1) {
                throw new Invalid("A limit is from 1 to 999");
            }
            columns.add(new BoardColumn(m.group(1).strip(), in, limit));
        }
        if (columns.size() > MAX) {
            throw new Invalid("At most " + MAX + " columns");
        }
        return columns;
    }

    /**
     * The columns as the board shows them (ADR-20, A.5): statuses no longer in the workflow are left out, and a status
     * of the workflow in no column goes to the first column holding a status of its category, else to the last column,
     * so a new status never hides cards. Nothing is written to the board.
     */
    static List<BoardColumn> place(List<BoardColumn> own, List<Status> statuses) {
        if (own.isEmpty()) {
            return own;
        }
        Map<String, String> category = statuses.stream().collect(Collectors.toMap(Status::code, Status::category));
        List<List<String>> in = new ArrayList<>();
        for (BoardColumn column : own) {
            in.add(new ArrayList<>(column.statuses().stream().filter(category::containsKey).toList()));
        }
        for (Status status : statuses) {
            if (in.stream().anyMatch(c -> c.contains(status.code()))) {
                continue;
            }
            int target = in.size() - 1;
            for (int i = 0; i < in.size(); i++) {
                if (in.get(i).stream().anyMatch(s -> category.get(s).equals(status.category()))) {
                    target = i;
                    break;
                }
            }
            in.get(target).add(status.code());
        }
        List<BoardColumn> placed = new ArrayList<>();
        for (int i = 0; i < own.size(); i++) {
            placed.add(new BoardColumn(own.get(i).name(), in.get(i), own.get(i).wipLimit()));
        }
        return placed;
    }

    static String format(List<BoardColumn> columns) {
        return columns.stream().map(c -> c.name() + ": " + String.join(", ", c.statuses())
                + (c.wipLimit() == null ? "" : " | " + c.wipLimit())).collect(Collectors.joining("\n"));
    }
}
