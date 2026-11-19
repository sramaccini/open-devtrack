/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * A password strength meter for the pages that set a password: registration, update and reset (ADR-24 point 2, O5;
 * ASVS 2.1.8). It scores length and variety, and a password of the short list below, or one made of a single
 * repeated or consecutive run, is weak whatever its length. It only advises: the policy of the realm decides.
 * No library, nothing sent anywhere.
 */
(function () {
  "use strict";

  // The commonest passwords of the public breach lists, and the words a user of this application would try first
  var COMMON = [
    "123456", "123456789", "12345678", "12345", "1234567", "1234567890", "111111", "000000", "123123", "654321",
    "password", "password1", "password123", "passw0rd", "qwerty", "qwerty123", "qwertyuiop", "abc123", "abcd1234",
    "iloveyou", "admin", "admin123", "administrator", "welcome", "welcome1", "letmein", "monkey", "dragon", "master",
    "sunshine", "princess", "football", "baseball", "shadow", "superman", "michael", "trustno1", "starwars", "login",
    "changeme", "secret", "zaq12wsx", "1q2w3e4r", "1qaz2wsx", "asdfghjkl", "computer", "freedom", "whatever",
    "opendevtrack", "devtrack", "keycloak", "jira", "github", "gitlab",
    "ciao", "ciao123", "amore", "juventus", "napoli", "roma", "milano", "forzainter", "forzamilan", "francesca",
    "alessandro", "giuseppe", "andrea", "martina", "chiara", "giulia", "lorenzo", "federica", "valentina"
  ];

  var TEXTS = {
    en: {
      label: "Password strength",
      levels: ["Too short", "Weak", "Fair", "Good", "Strong"],
      common: "A very common password: choose another one.",
      hint: "Longer is stronger: a phrase of a few unrelated words works well."
    },
    it: {
      label: "Robustezza della password",
      levels: ["Troppo corta", "Debole", "Discreta", "Buona", "Robusta"],
      common: "Una password molto comune: scegline un'altra.",
      hint: "Più è lunga, più è robusta: una frase di qualche parola scollegata funziona bene."
    }
  };

  function texts() {
    var lang = (document.documentElement.lang || "en").slice(0, 2).toLowerCase();
    return TEXTS[lang] || TEXTS.en;
  }

  /** Only one character repeated, or a run of consecutive characters (abcdef, 98765). */
  function trivial(value) {
    if (/^(.)\1*$/.test(value)) {
      return true;
    }
    var up = true;
    var down = true;
    for (var i = 1; i < value.length; i++) {
      var step = value.charCodeAt(i) - value.charCodeAt(i - 1);
      up = up && step === 1;
      down = down && step === -1;
    }
    return up || down;
  }

  /** 0 too short, 1 weak, 2 fair, 3 good, 4 strong; common tells why a long password is weak. */
  function score(value) {
    if (value.length < 8) {
      return { level: 0, common: false };
    }
    var plain = value.toLowerCase().replace(/[^a-z0-9]/g, "");
    var bare = plain.replace(/\d+$/, "");
    if (COMMON.indexOf(plain) >= 0 || COMMON.indexOf(bare) >= 0 || trivial(value)) {
      return { level: 1, common: true };
    }
    var classes = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter(function (re) { return re.test(value); }).length;
    var points = (value.length >= 12 ? 2 : 1) + (value.length >= 16 ? 1 : 0) + (classes >= 3 ? 1 : 0) +
      (classes === 4 ? 1 : 0);
    return { level: Math.max(1, Math.min(4, points)), common: false };
  }

  function attach(input) {
    var t = texts();
    var meter = document.createElement("div");
    meter.className = "odt-strength";
    meter.id = input.id + "-strength";
    meter.setAttribute("data-level", "0");
    meter.innerHTML = '<div class="odt-strength__bar" aria-hidden="true"><span></span><span></span><span></span>' +
      '<span></span></div><div class="odt-strength__text" role="status" aria-live="polite"></div>';
    var text = meter.querySelector(".odt-strength__text");
    var group = input.closest(".pf-v5-c-input-group") || input;
    group.parentNode.insertBefore(meter, group.nextSibling);
    var described = input.getAttribute("aria-describedby");
    input.setAttribute("aria-describedby", (described ? described + " " : "") + meter.id);

    function update() {
      if (!input.value) {
        meter.setAttribute("data-level", "0");
        text.textContent = t.hint;
        return;
      }
      var s = score(input.value);
      meter.setAttribute("data-level", String(s.level));
      text.textContent = t.label + ": " + t.levels[s.level] + (s.common ? ". " + t.common : "");
    }

    input.addEventListener("input", update);
    update();
  }

  function start() {
    // The new password: #password-new on update and reset, #password on the registration (which has a confirmation)
    var input = document.getElementById("password-new") ||
      (document.getElementById("password-confirm") ? document.getElementById("password") : null);
    if (input) {
      attach(input);
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
