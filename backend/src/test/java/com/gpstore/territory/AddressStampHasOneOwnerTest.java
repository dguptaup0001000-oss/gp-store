package com.gpstore.territory;

import com.gpstore.entity.Address;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The old shared stamp stays gone.
 *
 * <p>WHY A TEST RATHER THAN A DELETED COLUMN. {@code addresses.subzone_id} and
 * {@code addresses.subzone_locked} are still ON the table after V70, on
 * purpose: the release before this one reads them, so dropping them in the
 * same migration that moves the data would make a rollback a data-loss event.
 * They come out in a later migration.
 *
 * <p>That leaves a window in which two columns exist that look authoritative
 * and are not - the exact shape of bug that produces "why is this customer in
 * the wrong territory" six months later. So the rule is enforced here instead
 * of by the schema: nothing in the application reads or writes them, and the
 * territory stamp is per shop.
 *
 * <p>If this fails, do not add an exception - the answer is in
 * {@link AddressTerritory}.
 */
@DisplayName("A territory stamp belongs to one shop, and the old shared columns stay dead")
class AddressStampHasOneOwnerTest {

    @Test
    @DisplayName("an address carries no territory of its own")
    void theAddressHasNoStamp() {
        List<String> fields = Stream.of(Address.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .filter(name -> name.toLowerCase().contains("subzone"))
                .toList();

        assertTrue(fields.isEmpty(),
                "Address is back to carrying a territory: " + fields + ". One house has one row "
                        + "there and a marketplace has one map per shop over it, so the field can "
                        + "only ever hold one shop's answer - and the shops take it from each "
                        + "other. The stamp belongs in address_territory_stamps.");
    }

    @Test
    @DisplayName("the stamp table is shop-owned, so the tenant filter narrows it")
    void theStampIsShopOwned() {
        assertTrue(com.gpstore.platform.ShopOwned.class
                        .isAssignableFrom(AddressTerritoryStamp.class),
                "the stamp stopped being shop-owned, which is the only thing keeping one "
                        + "shop's answer out of another's reads");
        assertTrue(AddressTerritoryStamp.class
                        .isAnnotationPresent(org.hibernate.annotations.Filter.class),
                "the shop filter was removed from the stamp - every query in "
                        + "AddressTerritoryStampRepository relies on it and names no shop");
    }

    @Test
    @DisplayName("no code reads or writes the superseded columns")
    void nothingTouchesTheOldColumns() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path main = Path.of("src/main/java");

        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = withoutComments(Files.readString(file));
                for (String forbidden : new String[]{
                        "subzone_locked", "subzoneLocked",
                        "addresses.subzone_id", "a.subzone", "address.getSubzone"}) {
                    if (code.contains(forbidden)) {
                        offenders.add(main.relativize(file) + " -> " + forbidden);
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "these read or write the superseded shared columns, which hold one shop's "
                        + "answer for every shop: " + offenders);
    }

    /**
     * Strips comments, so the prose explaining why the columns are dead does
     * not itself trip the rule that says they are.
     */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean inBlock = false;
        for (String line : source.split("\n", -1)) {
            String rest = line;
            while (!rest.isEmpty()) {
                if (inBlock) {
                    int close = rest.indexOf("*/");
                    if (close < 0) {
                        rest = "";
                    } else {
                        rest = rest.substring(close + 2);
                        inBlock = false;
                    }
                    continue;
                }
                int lineComment = rest.indexOf("//");
                int blockComment = rest.indexOf("/*");
                if (lineComment >= 0 && (blockComment < 0 || lineComment < blockComment)) {
                    out.append(rest, 0, lineComment);
                    rest = "";
                } else if (blockComment >= 0) {
                    out.append(rest, 0, blockComment);
                    rest = rest.substring(blockComment + 2);
                    inBlock = true;
                } else {
                    out.append(rest);
                    rest = "";
                }
            }
            out.append('\n');
        }
        return out.toString();
    }
}
