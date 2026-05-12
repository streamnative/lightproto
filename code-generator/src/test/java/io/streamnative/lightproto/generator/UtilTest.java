/**
 * Copyright 2026 StreamNative
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.lightproto.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Locks the pluralize/singular behavior inherited from JiBX NameUtilities.
 * These functions feed into generated public API names (e.g. {@code addItem},
 * {@code getItemsCount}), so changing their output is an ABI break.
 */
class UtilTest {

    @ParameterizedTest
    @CsvSource({
            // default case: append 's'
            "item, items",
            "book, books",
            "Message, Messages",
            // ends with 'ss': append 'es'
            "address, addresses",
            "class, classes",
            // ends with consonant+'y': replace with 'ies'
            "city, cities",
            "category, categories",
            "company, companies",
            // ends with vowel+'y': default rule, append 's'
            "day, days",
            "key, keys",
            "boy, boys",
            "guy, guys",
            "way, ways",
            // 'any' is a hardcoded exception (case-insensitive match), but
            // only reached when endsWith("y") matches, which is case-sensitive.
            // So "ANY" slips through to the default rule.
            "any, any",
            "Any, Any",
            "ANY, ANYs",
            // already plural ('s' but not 'ss'): unchanged
            "items, items",
            "books, books",
            // ends with 'List': treated as already plural
            "itemList, itemList",
            "userList, userList",
    })
    void pluralizes(String input, String expected) {
        assertEquals(expected, Util.plural(input));
    }

    @ParameterizedTest
    @CsvSource({
            // typical snake_case
            "first_name, firstName",
            "user_id, userId",
            "foo_bar_baz, fooBarBaz",
            "snake_case_name, snakeCaseName",
            // no underscore: returned as-is (does NOT lowercase)
            "foo, foo",
            "MyService, MyService",
            // numerics
            "abc_123, abc123",
            // double underscore
            "foo__bar, fooBar",
            // leading underscore capitalizes the next char
            "_leading, Leading",
            "_msgSize, Msgsize",
            // trailing underscore is dropped
            "trailing_, trailing",
            // embedded uppercase is normalized to lowercase (matches Guava)
            "ALL_CAPS, allCaps",
            "Foo_Bar, fooBar",
            "FOO_BAR_BAZ, fooBarBaz",
    })
    void snakeToCamel(String input, String expected) {
        assertEquals(expected, Util.lowerUnderscoreToLowerCamel(input));
    }

    @ParameterizedTest
    @CsvSource({
            // typical camelCase
            "fooBar, foo_bar",
            "fooBarBaz, foo_bar_baz",
            "myService, my_service",
            // leading uppercase: lowercased without leading underscore
            "MyService, my_service",
            "X, x",
            // no uppercase: unchanged
            "foo, foo",
            "first_name, first_name",
            // numerics
            "abc123, abc123",
            // already snake-ish stays put
            "foo_bar_baz, foo_bar_baz",
    })
    void camelToSnake(String input, String expected) {
        assertEquals(expected, Util.lowerCamelToLowerUnderscore(input));
    }

    @ParameterizedTest
    @CsvSource({
            // ends with 'ies': replace with 'y'
            "cities, city",
            "categories, category",
            "companies, company",
            // ends with 'sses': strip 'es'
            "addresses, address",
            "classes, class",
            // ends with 's' (not 'ss'): strip 's'
            "items, item",
            "books, book",
            "days, day",
            // ends with 'List': strip suffix
            "itemList, item",
            "userList, user",
            // ends with 'ss': unchanged (not a plural)
            "address, address",
            "class, class",
            // no recognized plural marker: unchanged
            "item, item",
            "any, any",
    })
    void singularizes(String input, String expected) {
        assertEquals(expected, Util.singular(input));
    }
}
