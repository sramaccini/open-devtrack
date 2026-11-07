/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The ODQL language (ADR-16): a hand-written lexer and recursive-descent parser. Only the syntax is checked here; the
 * fields, the operators allowed on each and the values are checked by {@link OdqlCompiler}.
 *
 * <pre>
 * query   := [or] [ORDER BY order {, order}]
 * or      := and {OR and}
 * and     := not {AND not}
 * not     := NOT not | ( or ) | clause
 * clause  := field (= | != | &lt; | &lt;= | &gt; | &gt;= | ~ | !~) value
 *          | field [NOT] IN ( value {, value} ) | field [NOT] IN function
 *          | field IS [NOT] EMPTY
 * value   := word | "text" | function
 * function:= word ( )
 * order   := field [ASC | DESC]
 * </pre>
 */
final class Odql {

    /** The longest query accepted: a saved filter is at most this long too. */
    static final int MAX_LENGTH = 2000;

    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "in", "is", "empty", "order", "by", "asc",
            "desc");

    private Odql() {
    }

    /** A query: a condition (null for every issue) and an order (empty for the default one). */
    record Query(Expr where, List<Order> order) {
    }

    sealed interface Expr permits And, Or, Not, Clause {
    }

    record And(Expr left, Expr right) implements Expr {
    }

    record Or(Expr left, Expr right) implements Expr {
    }

    record Not(Expr expr) implements Expr {
    }

    /**
     * {@code field op values}; {@code op} is one of {@code = != < <= > >= ~ !~ in notin empty notempty}; {@code at} is
     * the position of the field in the query, for the errors.
     */
    record Clause(String field, String op, List<Value> values, int at) implements Expr {
    }

    /** A word, a quoted text or a function ({@code currentUser()}); {@code text} is the name for a function. */
    record Value(String text, boolean quoted, boolean function, int at) {
    }

    record Order(String field, boolean descending, int at) {
    }

    /** A query that is not ODQL: {@code position} counts from 1, as an editor does. */
    static final class OdqlException extends RuntimeException {

        private final int position;
        private final String expected;

        OdqlException(int position, String message, String expected) {
            super(message);
            this.position = position;
            this.expected = expected;
        }

        int position() {
            return position;
        }

        String expected() {
            return expected;
        }
    }

    static Query parse(String text) {
        String query = text == null ? "" : text;
        if (query.length() > MAX_LENGTH) {
            throw new OdqlException(MAX_LENGTH + 1, "The query is longer than " + MAX_LENGTH + " characters", null);
        }
        return new Parser(tokens(query)).query();
    }

    // Lexer

    enum Kind { WORD, TEXT, SYMBOL, END }

    record Token(Kind kind, String text, int at) {

        boolean is(String keyword) {
            return kind == Kind.WORD && text.toLowerCase(Locale.ROOT).equals(keyword);
        }

        boolean symbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        boolean keyword() {
            return kind == Kind.WORD && KEYWORDS.contains(text.toLowerCase(Locale.ROOT));
        }
    }

    /** The characters of a word: letters, digits and {@code _ . - + : @}, so keys, dates and {@code -14d} are words. */
    static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '+' || c == ':' || c == '@';
    }

    static List<Token> tokens(String query) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"') {
                int start = i++;
                StringBuilder text = new StringBuilder();
                while (i < query.length() && query.charAt(i) != '"') {
                    if (query.charAt(i) == '\\' && i + 1 < query.length()) {
                        i++;
                    }
                    text.append(query.charAt(i++));
                }
                if (i >= query.length()) {
                    throw new OdqlException(start + 1, "The text that starts here has no closing quote", "\"");
                }
                i++;
                tokens.add(new Token(Kind.TEXT, text.toString(), start + 1));
            } else if (wordChar(c)) {
                int start = i;
                while (i < query.length() && wordChar(query.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.WORD, query.substring(start, i), start + 1));
            } else if (c == '!' || c == '<' || c == '>') {
                int start = i++;
                if (i < query.length() && (query.charAt(i) == '=' || (c == '!' && query.charAt(i) == '~'))) {
                    i++;
                } else if (c == '!') {
                    throw new OdqlException(start + 1, "Expected != or !~", "!= !~");
                }
                tokens.add(new Token(Kind.SYMBOL, query.substring(start, i), start + 1));
            } else if ("=~(),".indexOf(c) >= 0) {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), i + 1));
                i++;
            } else {
                throw new OdqlException(i + 1, "Unexpected character '" + c + "'", null);
            }
        }
        tokens.add(new Token(Kind.END, "", query.length() + 1));
        return tokens;
    }

    // Parser

    private static final class Parser {

        private final List<Token> tokens;
        private int next;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Query query() {
            Expr where = null;
            if (!peek().is("order") && peek().kind() != Kind.END) {
                where = or();
            }
            List<Order> order = new ArrayList<>();
            if (peek().is("order")) {
                take();
                expectKeyword("by");
                do {
                    Token field = expectField();
                    boolean descending = false;
                    if (peek().is("asc")) {
                        take();
                    } else if (peek().is("desc")) {
                        take();
                        descending = true;
                    }
                    order.add(new Order(field.text(), descending, field.at()));
                } while (accept(","));
            }
            Token end = peek();
            if (end.kind() != Kind.END) {
                throw new OdqlException(end.at(), "Unexpected '" + end.text() + "'", "AND OR ORDER BY");
            }
            return new Query(where, List.copyOf(order));
        }

        private Expr or() {
            Expr left = and();
            while (peek().is("or")) {
                take();
                left = new Or(left, and());
            }
            return left;
        }

        private Expr and() {
            Expr left = not();
            while (peek().is("and")) {
                take();
                left = new And(left, not());
            }
            return left;
        }

        private Expr not() {
            if (peek().is("not")) {
                take();
                return new Not(not());
            }
            if (accept("(")) {
                Expr inner = or();
                expectSymbol(")");
                return inner;
            }
            return clause();
        }

        private Expr clause() {
            Token field = expectField();
            Token op = peek();
            if (op.kind() == Kind.SYMBOL && Set.of("=", "!=", "<", "<=", ">", ">=", "~", "!~").contains(op.text())) {
                take();
                return new Clause(field.text(), op.text(), List.of(value()), field.at());
            }
            if (op.is("not") || op.is("in")) {
                take();
                boolean negated = op.is("not");
                if (negated) {
                    expectKeyword("in");
                }
                return new Clause(field.text(), negated ? "notin" : "in", list(), field.at());
            }
            if (op.is("is")) {
                take();
                boolean negated = false;
                if (peek().is("not")) {
                    take();
                    negated = true;
                }
                expectKeyword("empty");
                return new Clause(field.text(), negated ? "notempty" : "empty", List.of(), field.at());
            }
            throw new OdqlException(op.at(), op.kind() == Kind.END ? "Expected an operator after " + field.text()
                    : "Expected an operator, not '" + op.text() + "'", "= != < <= > >= ~ !~ IN NOT IN IS EMPTY");
        }

        /** {@code ( value, … )} or a function that gives a list ({@code openSprints()}). */
        private List<Value> list() {
            if (!peek().symbol("(")) {
                Value function = value();
                if (!function.function()) {
                    throw new OdqlException(function.at(), "Expected ( and a list of values after IN", "(");
                }
                return List.of(function);
            }
            take();
            List<Value> values = new ArrayList<>();
            do {
                values.add(value());
            } while (accept(","));
            expectSymbol(")");
            return List.copyOf(values);
        }

        private Value value() {
            Token token = peek();
            if (token.kind() == Kind.TEXT) {
                take();
                return new Value(token.text(), true, false, token.at());
            }
            if (token.kind() == Kind.WORD && !token.keyword()) {
                take();
                if (peek().symbol("(")) {
                    take();
                    expectSymbol(")");
                    return new Value(token.text(), false, true, token.at());
                }
                return new Value(token.text(), false, false, token.at());
            }
            throw new OdqlException(token.at(), token.kind() == Kind.END ? "Expected a value at the end"
                    : "Expected a value, not '" + token.text() + "'", "a value");
        }

        private Token expectField() {
            Token token = peek();
            if (token.kind() != Kind.WORD || token.keyword()) {
                throw new OdqlException(token.at(), token.kind() == Kind.END ? "Expected a field at the end"
                        : "Expected a field, not '" + token.text() + "'", "a field");
            }
            return take();
        }

        private void expectKeyword(String keyword) {
            Token token = peek();
            if (!token.is(keyword)) {
                throw new OdqlException(token.at(), "Expected " + keyword.toUpperCase(Locale.ROOT),
                        keyword.toUpperCase(Locale.ROOT));
            }
            take();
        }

        private void expectSymbol(String symbol) {
            Token token = peek();
            if (!token.symbol(symbol)) {
                throw new OdqlException(token.at(), "Expected " + symbol, symbol);
            }
            take();
        }

        private boolean accept(String symbol) {
            if (peek().symbol(symbol)) {
                take();
                return true;
            }
            return false;
        }

        private Token peek() {
            return tokens.get(next);
        }

        private Token take() {
            return tokens.get(next++);
        }
    }
}
