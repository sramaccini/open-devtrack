/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Adapted from the Quadrava reference registry (reference/registry, commit 9fa1916), contracts/registry.yaml 0.6.0.
 */
package com.logicwarez.opendevtrack.uiregistry;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicwarez.opendevtrack.uiregistry.Model.DataSourcesReference;
import com.logicwarez.opendevtrack.uiregistry.Model.Kind;
import com.logicwarez.opendevtrack.uiregistry.Model.ScreenReference;
import org.junit.jupiter.api.Test;

/** References by identifier to other screens, from the navigation and from screens. */
class ReferencesTest {

    @Test
    void theNavigationCitesTheScreensOfItsItemsEvenNested() {
        String navigation = """
                {"schemaVersion":"1.0","navigation":"principale","default":"invoices","areas":[
                  {"id":"invoices","label":"Fatture","items":[
                    {"id":"elenco","label":"Elenco","screen":"invoice-list"},
                    {"id":"nuova","label":"Nuova","children":[{"id":"sales","label":"Attiva","screen":"invoice-new"}]}]}]}""";
        assertThat(References.of(Kind.NAVIGATION, "principale", navigation)).containsExactly(
                new ScreenReference("invoice-list", "/areas/0/items/0/screen"),
                new ScreenReference("invoice-new", "/areas/0/items/1/children/0/screen"));
    }

    @Test
    void aScreenCitesNavigateStepsAndLinkNodesButNotItselfNorExpressions() {
        String screen = """
                {"schemaVersion":"1.0","screen":"order-list","components":[
                  {"type":"link","label":"Dettaglio","screen":"order-detail"},
                  {"type":"link","label":"Esterno","href":"https://example.org"},
                  {"type":"link","label":"Dinamico","screen":"{{ state.destinazione }}"},
                  {"type":"button","label":"Ricarica","onClick":[{"navigate":"order-list"}]}],
                 "actions":{"open":[{"navigate":"order-new","params":{"from":"list"}}]}}""";
        assertThat(References.of(Kind.SCREEN, "order-list", screen)).containsExactly(
                new ScreenReference("order-detail", "/components/0/screen"),
                new ScreenReference("order-new", "/actions/open/0/navigate"));
    }

    @Test
    void aScreenCitesTheDataSourcesDocumentsOfItsReferences() {
        String screen = """
                {"schemaVersion":"1.0","screen":"order-list","dataSources":{
                  "orders":{"ref":"orders/list","params":{"status":"open"}},
                  "local":{"type":"rest","method":"GET","url":"/api/local"},
                  "approve":{"ref":"orders/approve"}},"components":[]}""";
        assertThat(References.dataSourcesOf(Kind.SCREEN, screen)).containsExactly(
                new DataSourcesReference("orders", "/dataSources/orders/ref"),
                new DataSourcesReference("orders", "/dataSources/approve/ref"));
        assertThat(References.dataSourcesOf(Kind.NAVIGATION, screen)).isEmpty();
    }

    @Test
    void themesAndCataloguesHaveNoReferences() {
        assertThat(References.of(Kind.THEME, "light", "{\"schemaVersion\":\"1.0\",\"theme\":\"light\",\"screen\":\"x\"}")).isEmpty();
    }
}
