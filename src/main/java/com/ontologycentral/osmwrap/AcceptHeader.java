package com.ontologycentral.osmwrap;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class AcceptHeader {

    public record AcceptType(String type, String subtype, double q) {}

    /** Parse an HTTP Accept header into a q-sorted list. */
    public static List<AcceptType> parse(String header) {
        if (header == null || header.isBlank()) return List.of();
        return Arrays.stream(header.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(part -> {
                String[] pieces = part.split(";\\s*q\\s*=");
                String[] types  = pieces[0].trim().split("/");
                if (types.length != 2) return null;
                double q = pieces.length > 1 ? Double.parseDouble(pieces[1].trim()) : 1.0;
                return new AcceptType(types[0].trim(), types[1].trim(), q);
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingDouble(AcceptType::q).reversed())
            .toList();
    }

    /** True if the parsed list includes the given type/subtype with q > 0. */
    public static boolean accepts(List<AcceptType> list, String type, String subtype) {
        return list.stream().anyMatch(a -> a.q() > 0
            && (a.type().equals("*")    || a.type().equals(type))
            && (a.subtype().equals("*") || a.subtype().equals(subtype)));
    }

    /** Among two offered types, return true if `type1/sub1` scores higher than `type2/sub2`. */
    public static boolean prefers(List<AcceptType> list,
                                  String type1, String sub1,
                                  String type2, String sub2) {
        double q1 = list.stream()
            .filter(a -> (a.type().equals("*") || a.type().equals(type1))
                      && (a.subtype().equals("*") || a.subtype().equals(sub1)))
            .mapToDouble(AcceptType::q).max().orElse(0.0);
        double q2 = list.stream()
            .filter(a -> (a.type().equals("*") || a.type().equals(type2))
                      && (a.subtype().equals("*") || a.subtype().equals(sub2)))
            .mapToDouble(AcceptType::q).max().orElse(0.0);
        return q1 > q2;
    }
}
