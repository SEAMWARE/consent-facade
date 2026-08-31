/*
 * Copyright 2026 Seamless Middleware Technologies S.L and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fiware.consent.mapping;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings an ODRL policy into the shape {@code OdrlPolicyVO} binds to, whichever of the two forms it
 * arrives in.
 *
 * <p>A provider declares its ODRL <strong>once</strong>, on the product specification, and two very
 * different components read it: the ODRL PAP, which requires the JSON-LD form
 * ({@code odrl:uid}, {@code odrl:permission}, {@code odrl:target: {"@id": ...}}), and this facade,
 * whose contract API models a rule's {@code target} and {@code action} as plain strings. Rather than
 * asking a provider to declare the same policy twice, this normalizes the JSON-LD form on the way in.
 *
 * <p>Three transformations, each idempotent so an already-plain policy passes through unchanged:
 * <ol>
 *   <li><strong>Key prefixes.</strong> A leading {@code odrl:} is stripped from every key, recursively.</li>
 *   <li><strong>Single rules.</strong> JSON-LD writes one permission as an object; the contract API
 *       (and the consent-manager behind it) expects an array. A lone rule object is wrapped.</li>
 *   <li><strong>Node objects.</strong> Fields the contract API types as strings arrive as JSON-LD
 *       nodes: {@code {"@id": "urn:x"}} collapses to {@code "urn:x"}, and an
 *       {@code odrl:AssetCollection} collapses to its {@code odrl:source}.</li>
 * </ol>
 *
 * <p>JSON-LD housekeeping keys ({@code @context}, {@code @id}) are dropped: they carry no contract
 * information and the target type has no field for them.
 *
 * <p>Note the limit of collapsing an {@code AssetCollection} to its {@code source}: the collection's
 * refinements are what actually narrow it (e.g. to one entity type), and they are lost. The resulting
 * target is the collection's source URI, which a data-plane enforcer matching plain URIs will not
 * equate with a concrete object. That is a property of the contract model, not of this conversion -
 * expressing such a target faithfully needs the model to carry collections.
 */
@Singleton
@Slf4j
public class OdrlNormalizer {

    /** The prefix the JSON-LD form puts on every ODRL term. */
    private static final String ODRL_PREFIX = "odrl:";

    /** JSON-LD keys that carry no contract information and have no field to bind to. */
    private static final Set<String> DROPPED_KEYS = Set.of("@context", "@id");

    /** Rule collections that must be arrays, even when a single rule was written as an object. */
    private static final Set<String> RULE_COLLECTIONS = Set.of("permission", "prohibition", "obligation");

    /** Fields the contract API types as a plain string but JSON-LD may write as a node object. */
    private static final Set<String> SCALAR_FIELDS =
            Set.of("target", "assetTarget", "action", "assigner", "assignee",
                    "leftOperand", "operator", "rightOperand", "uid");

    /** The JSON-LD key naming a node's identity. */
    private static final String ID_KEY = "@id";

    /** The keys an {@code AssetCollection} names its source asset with, prefixed or not. */
    private static final List<String> SOURCE_KEYS = List.of("odrl:source", "source");

    /**
     * Normalizes a raw policy value.
     *
     * @param rawPolicy the {@code policy} characteristic value, in either ODRL form
     * @return the normalized value, ready to bind to the contract API's policy type
     */
    public Object normalize(Object rawPolicy) {
        return normalizeValue(rawPolicy);
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            list.forEach(element -> normalized.add(normalizeValue(element)));
            return normalized;
        }
        return value;
    }

    private Map<String, Object> normalizeMap(Map<?, ?> map) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        map.forEach((rawKey, rawValue) -> {
            if (rawKey == null) {
                return;
            }
            String key = stripPrefix(rawKey.toString());
            if (DROPPED_KEYS.contains(key)) {
                return;
            }
            Object value;
            if (SCALAR_FIELDS.contains(key)) {
                // Collapse from the RAW value: the recursive pass drops the JSON-LD housekeeping
                // keys, and `@id` is exactly what a node collapses to.
                value = toScalar(key, rawValue);
            } else {
                value = normalizeValue(rawValue);
                if (RULE_COLLECTIONS.contains(key) && !(value instanceof List)) {
                    value = new ArrayList<>(List.of(value));
                }
            }
            normalized.put(key, value);
        });
        return normalized;
    }

    /** Collapses a JSON-LD node to the string the contract API expects, or leaves a scalar alone. */
    private Object toScalar(String key, Object value) {
        if (!(value instanceof Map<?, ?> node)) {
            return value;
        }
        Object id = node.get(ID_KEY);
        if (id instanceof String identifier && !identifier.isBlank()) {
            return identifier;
        }
        for (String sourceKey : SOURCE_KEYS) {
            if (node.get(sourceKey) instanceof String sourceUri && !sourceUri.isBlank()) {
                log.debug("Collapsed an asset collection to its source for '{}'; its refinements are not "
                        + "representable in the contract model.", key);
                return sourceUri;
            }
        }
        log.debug("Could not collapse the node at '{}' to a scalar; dropping it.", key);
        return null;
    }

    /** Strips the {@code odrl:} prefix from a key, leaving other keys (and {@code @type}) alone. */
    private static String stripPrefix(String key) {
        return key.startsWith(ODRL_PREFIX) ? key.substring(ODRL_PREFIX.length()) : key;
    }
}
