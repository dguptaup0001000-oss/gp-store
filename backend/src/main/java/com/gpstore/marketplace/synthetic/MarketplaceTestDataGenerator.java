package com.gpstore.marketplace.synthetic;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;
import com.gpstore.catalog.shop.OfflineAvailability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Deterministic, image-free specifications for the controlled 100-shop test batch. */
public final class MarketplaceTestDataGenerator {

    public static final String BATCH_ID = "MARKETPLACE_TEST_100_SHOPS_V1";
    public static final int SHOP_COUNT = 100;
    public static final int MERCHANT_COUNT = 95;
    /** The first shipped revision contained this many rows per shop. */
    public static final int BASE_LISTINGS_PER_SHOP = 12;
    /** Keep well above the accepted minimum of fifty rows per synthetic shop. */
    public static final int MINIMUM_LISTINGS_PER_SHOP = 50;
    public static final int LISTINGS_PER_SHOP = 60;
    public static final int LISTING_COUNT = SHOP_COUNT * LISTINGS_PER_SHOP;
    public static final long DEFAULT_SEED = 20260925L;

    private static final String[] SURNAMES = {
            "Sharma", "Gupta", "Verma", "Singh", "Khan", "Yadav", "Mishra", "Patel",
            "Rai", "Srivastava", "Agarwal", "Maurya", "Tripathi", "Jaiswal", "Chauhan",
            "Pandey", "Sahu", "Tiwari", "Ansari", "Rathore"
    };
    private static final String[] LOCALITIES = {
            "Golghar", "Betiahata", "Rustampur", "Taramandal", "Mohanlalpur", "Padri Bazar",
            "Medical Road", "Rapti Nagar", "Asuran", "Gida", "Nausar", "Chargawan",
            "Pipraich", "Sahjanwa", "Khorabar", "Kushinagar", "Deoria Road", "Basharatpur",
            "Shahpur", "Rajendra Nagar"
    };
    private static final String[] PACKS = {
            "250 g", "500 g", "1 kg", "2 kg", "1 L", "500 ml", "Pack of 2", "Pack of 4"
    };
    private static final String[] COLOURS = {"Black", "Blue", "White", "Red", "Grey", "Green"};
    private static final String[] BRANDS = {
            "Market Basics", "Test Select", "Everyday Choice", "Local Choice", "GP Demo"
    };
    private static final String[] OFFLINE_AVAILABILITY = {
            "AVAILABLE", "AVAILABLE", "AVAILABLE", "LIMITED_AVAILABILITY", "MADE_TO_ORDER",
            "OUT_OF_STOCK"
    };

    private static final List<ShopType> TYPES = List.of(
            new ShopType(12, "Kirana and General Store", "Grocery", 12, 0, 0,
                    "Iodised Salt|Whole Wheat Atta|Basmati Rice|Toor Dal|Turmeric Powder|Mustard Oil|Tea Leaves|Digestive Biscuits|Bathing Soap|Dishwash Liquid|Detergent Powder|Poha|Jaggery|Chana|Groundnut Oil"),
            new ShopType(6, "Fresh Fruit and Vegetable Market", "Fruits and Vegetables", 10, 2, 0,
                    "Banana|Apple|Orange|Papaya|Potato|Tomato|Onion|Cauliflower|Green Peas|Coriander|Seasonal Fruit Basket|Fresh Vegetable Basket"),
            new ShopType(5, "Dairy and Bake House", "Dairy and Bakery", 12, 0, 0,
                    "Full Cream Milk|Curd Cup|Paneer|Salted Butter|Fresh Cream|Milk Bread|Brown Bread|Pav Buns|Rusk|Tea Cake|Cupcake Box|Khari Biscuits"),
            new ShopType(6, "Mobile and Electronics Store", "Mobile and Electronics", 4, 6, 2,
                    "USB-C Cable|Fast Charger|Phone Case|Tempered Glass|Wireless Earbuds|Power Bank|Smartphone|Feature Phone|Bluetooth Speaker|Screen Guard|Mobile Screen Repair|Battery Replacement|Charging Port Repair"),
            new ShopType(5, "Computer and Accessories Centre", "Computers and Accessories", 4, 6, 2,
                    "Wireless Mouse|Keyboard|Laptop Stand|USB Drive|Wi-Fi Adapter|Laptop|Desktop Computer|Printer|Laptop Bag|Webcam|Laptop Repair|Printer Repair"),
            new ShopType(7, "Clothing and Fashion House", "Clothing and Fashion", 5, 7, 0,
                    "Cotton Kurta|Casual Shirt|Printed Saree|Leggings|Kids T-Shirt|School Uniform|Denim Jeans|Dupatta|Night Suit|Blouse Piece|Jacket|Track Pants"),
            new ShopType(5, "Footwear Store", "Footwear", 5, 7, 0,
                    "Walking Shoes|School Shoes|Leather Sandals|Rubber Slippers|Sports Socks|Rain Boots|Canvas Shoes|Shoe Care Kit|Formal Shoes|Ladies Sandals|Kids Shoes|Shoe Repair Service"),
            new ShopType(6, "Hardware and Electrical Store", "Hardware and Electrical", 6, 5, 1,
                    "Screw Assortment|Wall Plug Set|Door Hinge|Padlock|Paint Brush|Measuring Tape|Hand Saw|Steel Pipe|Water Tank|Drill Machine|Door Fitting Service"),
            new ShopType(5, "Furniture and Home Store", "Furniture and Home", 2, 10, 0,
                    "Study Table|Dining Chair|Bookshelf|Single Bed|Sofa Set|Mattress|Wardrobe|Coffee Table|Furniture Assembly|Furniture Repair"),
            new ShopType(4, "Jewellery and Accessories Showroom", "Jewellery and Accessories", 0, 12, 0,
                    "Gold Ring|Silver Anklet|Gold Chain|Wedding Necklace|Silver Coin|Diamond Ring|Gold Earrings|Bangle Set|Pendant|Jewellery Cleaning|Costume Jewellery|Bracelet"),
            new ShopType(5, "Family Restaurant", "Restaurant and Food", 10, 2, 0,
                    "Veg Thali|Paneer Curry|Dal Tadka|Jeera Rice|Tandoori Roti|Veg Biryani|Masala Dosa|Chole Bhature|Lassi|Gulab Jamun"),
            new ShopType(3, "Sweets and Bake House", "Sweets and Bakery", 12, 0, 0,
                    "Gulab Jamun Box|Rasgulla Box|Kaju Katli|Besan Ladoo|Motichoor Ladoo|Milk Cake|Soan Papdi|Fresh Jalebi|Namkeen Mix|Samosa Box|Celebration Cake|Dry Fruit Box"),
            new ShopType(4, "Auto Parts Centre", "Automotive Parts", 5, 6, 1,
                    "Wiper Blade Set|Car Air Filter|Engine Oil Can|Car Floor Mat|Headlamp Bulb|Battery Cable|Tyre Inflator|Seat Cover Set|Car Accessories Fitment"),
            new ShopType(4, "Tractor and Agricultural Parts Centre", "Tractor and Agricultural Parts", 4, 7, 1,
                    "Tractor Brake Pad|Clutch Plate|Hydraulic Filter|Tractor Headlamp|Fuel Filter|Fan Belt|Steering Joint|PTO Shaft Guard|Tractor Service Check|Brake Inspection|Bearing Set|Oil Seal Kit"),
            new ShopType(4, "Beauty and Personal Care", "Beauty and Personal Care", 10, 2, 0,
                    "Face Wash|Moisturising Lotion|Hair Oil|Shampoo|Conditioner|Lip Balm|Kajal Pencil|Nail Polish|Sunscreen|Comb Set|Body Lotion|Hair Brush"),
            new ShopType(3, "Books and Stationery Mart", "Books and Stationery", 10, 2, 0,
                    "Notebook|Ball Pen Set|Geometry Box|Drawing Colours|Printer Paper|School Bag|Stapler|Desk Organiser|Marker Set|Art Paper|Hindi Dictionary|Exam Guide"),
            new ShopType(3, "Home Services and Repair", "Home Services and Repair", 2, 0, 10,
                    "Tap Washer Set|LED Bulb Pair|Plumbing Inspection|Tap Repair|Furniture Assembly|Home Deep Cleaning|Water Filter Service|Ceiling Fan Installation|Electrician Visit|AC Repair|Appliance Inspection|Door Lock Repair"),
            new ShopType(3, "Mobile Repair Studio", "Mobile and Electronics Repair", 2, 0, 10,
                    "Charging Cable|Phone Case|Mobile Screen Repair|Battery Replacement|Charging Port Repair|Speaker Repair|Water Damage Inspection|Software Setup|Camera Repair|Microphone Repair|Phone Diagnosis|Data Transfer"),
            // Deliberately non-prescription: this exercises health-supply
            // discovery without inventing licences or regulated medicine claims.
            new ShopType(2, "Health Essentials Store", "Health and Medical Test Supplies", 10, 2, 0,
                    "Digital Thermometer|Reusable Hot Water Bag|First Aid Box|Cotton Roll|Crepe Bandage|Adult Walking Stick|Pill Organiser|Digital Weighing Scale|Surgical Mask Pack|Hand Sanitiser|Ice Pack|Gauze Roll"),
            new ShopType(2, "Kitchenware Shop", "Kitchenware", 9, 3, 0,
                    "Steel Plate Set|Pressure Cooker|Nonstick Pan|Storage Container|Water Bottle|Knife Set|Lunch Box|Gas Lighter|Kitchen Rack|Cookware Polish|Tea Strainer|Spice Box"),
            new ShopType(2, "Electrical Goods Store", "Electrical Supplies", 7, 4, 1,
                    "LED Bulb|Switch Board|Extension Cord|Ceiling Fan Regulator|Copper Wire Coil|MCB Switch|Tube Light|Electrical Repair Visit"),
            new ShopType(2, "Gift and Decor House", "Gifts and Home Decor", 6, 6, 0,
                    "Ceramic Mug Set|Photo Frame|Desk Lamp|Wall Clock|Gift Hamper|Decorative Vase|Festival Lights|Greeting Card Pack|Name Plate"),
            new ShopType(2, "Toy and Play Store", "Toys and Kids", 8, 4, 0,
                    "Building Block Set|Soft Toy|Jigsaw Puzzle|Remote Car|Colouring Book|Board Game|Baby Rattle|Outdoor Ball|Learning Flash Cards|Toy Train|Cricket Set|Puzzle Cube")
    );

    private MarketplaceTestDataGenerator() { }

    public record ShopSpec(int ordinal, String shopName, String merchantName, String shopCode,
                           String shopType, String category, String locality,
                           double distanceKm, double bearingRadians,
                           BigDecimal deliveryRadiusKm, int buyOnlineCount, int visitToBuyCount,
                           int serviceCount) { }

    public record ListingSpec(int shopOrdinal, String shopCode, String shopName,
                              String productName, String brand, String category,
                              String subcategory, String description, String sku,
                              String quantityLabel, double quantity, String unit,
                              BigDecimal sellingPrice, BigDecimal mrp, BigDecimal costPrice,
                              int stock, boolean bestseller, boolean featured,
                              CommerceMode commerceMode, ListingPriceMode priceMode,
                              BigDecimal priceMax, OfflineAvailability offlineAvailability,
                              int serviceDurationMinutes) { }

    public record Dataset(List<ShopSpec> shops, List<ListingSpec> listings) { }

    public static Dataset generate(long seed) {
        return generate(seed, SHOP_COUNT);
    }

    /**
     * Generates only the number of synthetic shops needed to bring an
     * existing marketplace to {@link #SHOP_COUNT}. Ordinals and SKUs remain
     * stable prefixes of the full deterministic dataset, so reruns cannot
     * create a second identity for the same intended shop.
     */
    public static Dataset generate(long seed, int shopCount) {
        if (shopCount < 0 || shopCount > SHOP_COUNT) {
            throw new IllegalArgumentException("shopCount must be between 0 and " + SHOP_COUNT);
        }
        Random random = new Random(seed);
        List<ShopSpec> shops = new ArrayList<>(shopCount);
        List<ListingSpec> listings = new ArrayList<>(shopCount * LISTINGS_PER_SHOP);
        BigDecimal[] radii = {
                new BigDecimal("2.00"), new BigDecimal("5.00"), new BigDecimal("8.00"),
                new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("50.00"),
                new BigDecimal("100.00")
        };

        for (int shopNumber = 1; shopNumber <= shopCount; shopNumber++) {
            int ordinal = shopNumber - 1;
            ShopType type = typeForOrdinal(ordinal);
            String surname = SURNAMES[(ordinal * 7) % SURNAMES.length];
            String locality = LOCALITIES[(ordinal * 11) % LOCALITIES.length];
            String shopName = surname + " " + type.label + " - " + locality;
            String shopCode = String.format("MKT100V1-SHOP-%03d", shopNumber);
            double distanceKm = distanceFor(ordinal);
            double bearingRadians = Math.toRadians((shopNumber * 137.507764) % 360.0);
            List<BigDecimal> eligibleRadii = Arrays.stream(radii)
                    // Leave a small geodesic rounding margin for the
                    // repository's persisted decimal coordinates.
                    .filter(radius -> radius.doubleValue() >= distanceKm + 0.1d)
                    .toList();
            BigDecimal shopRadius = eligibleRadii.get((shopNumber - 1) % eligibleRadii.size());
            int cycles = LISTINGS_PER_SHOP / BASE_LISTINGS_PER_SHOP;
            int[] counts = {type.buyOnline * cycles, type.visitToBuy * cycles,
                    type.service * cycles};
            shops.add(new ShopSpec(shopNumber, shopName,
                    "[" + BATCH_ID + "] Merchant " + String.format("%03d",
                            merchantNumber(shopNumber)),
                    shopCode, type.label, type.category, locality, distanceKm, bearingRadians,
                    shopRadius, counts[0], counts[1], counts[2]));

            int listingNumber = 0;
            // Cycle zero is byte-for-byte deterministic with the original
            // twelve-listing generator. Later cycles append new SKUs, which
            // lets production safely expand the already-created test batch
            // without deleting or rewriting those rows.
            for (int cycle = 0; cycle < cycles; cycle++) {
                for (int i = 0; i < type.buyOnline; i++) {
                    listings.add(makeListing(random, shopNumber, shopCode, shopName, type,
                            CommerceMode.ONLINE_PURCHASE,
                            cycle * type.buyOnline + i, listingNumber++));
                }
                for (int i = 0; i < type.visitToBuy; i++) {
                    listings.add(makeListing(random, shopNumber, shopCode, shopName, type,
                            CommerceMode.VISIT_TO_BUY,
                            cycle * type.visitToBuy + i, listingNumber++));
                }
                for (int i = 0; i < type.service; i++) {
                    listings.add(makeListing(random, shopNumber, shopCode, shopName, type,
                            CommerceMode.SERVICE_AT_SHOP,
                            cycle * type.service + i, listingNumber++));
                }
            }
        }
        return new Dataset(List.copyOf(shops), List.copyOf(listings));
    }

    private static ShopType typeForOrdinal(int ordinal) {
        int at = ordinal;
        for (ShopType type : TYPES) {
            if (at < type.shopCount) return type;
            at -= type.shopCount;
        }
        throw new IllegalStateException("Shop type distribution does not define ordinal " + ordinal);
    }

    private static double distanceFor(int ordinal) {
        if (ordinal < 20) return 0.3d + ordinal * 0.08d;
        if (ordinal < 40) return 2.2d + (ordinal - 20) * 0.13d;
        if (ordinal < 60) return 5.1d + (ordinal - 40) * 0.14d;
        if (ordinal < 70) return 8.2d + (ordinal - 60) * 0.17d;
        if (ordinal < 85) return 10.5d + (ordinal - 70) * 0.62d;
        if (ordinal < 95) return 22.0d + (ordinal - 85) * 2.8d;
        return 55.0d + (ordinal - 95) * 10.0d;
    }

    private static int merchantNumber(int shopNumber) {
        return switch (shopNumber) {
            case 2 -> 1;
            case 25 -> 24;
            case 36 -> 35;
            case 48 -> 47;
            case 89 -> 88;
            default -> shopNumber;
        };
    }

    /** Categories owned by this synthetic batch, used by guarded cleanup. */
    public static List<String> categories() {
        return TYPES.stream().map(ShopType::category).distinct().sorted().toList();
    }

    private static ListingSpec makeListing(Random random, int shopNumber, String shopCode,
                                            String shopName, ShopType type, CommerceMode mode,
                                            int modeOrdinal, int listingOrdinal) {
        String[] names = type.products.split("\\|");
        String item = names[modeOrdinal % names.length];
        String pack = mode == CommerceMode.SERVICE_AT_SHOP
                ? new String[]{"Basic visit", "Standard service", "Inspection", "Per item"}[modeOrdinal % 4]
                : PACKS[(modeOrdinal + shopNumber) % PACKS.length];
        String colour = COLOURS[(modeOrdinal + shopNumber * 3) % COLOURS.length];
        String sku = String.format("MKT100V1-%03d-%03d", shopNumber, listingOrdinal + 1);
        String productName = item + " " + pack + " TEST " + String.format("%03d-%03d", shopNumber,
                listingOrdinal + 1);
        String brand = mode == CommerceMode.SERVICE_AT_SHOP ? "Local Test Service" :
                BRANDS[(modeOrdinal + shopNumber) % BRANDS.length];
        String description = switch (mode) {
            case ONLINE_PURCHASE -> productName + " from " + brand + ", listed by " + shopName
                    + " in " + type.category + ". " + colour + " option; synthetic test listing " + sku + ".";
            case VISIT_TO_BUY -> productName + " is displayed by " + shopName
                    + " for in-store inspection, fitting or selection before purchase. "
                    + "Indicative test listing " + sku + ".";
            case SERVICE_AT_SHOP -> productName + " is performed at " + shopName + ". "
                    + "Ask the shop to confirm the exact work and final price; this is an indicative "
                    + "synthetic test service " + sku + ".";
        };

        BigDecimal base = BigDecimal.valueOf(35 + random.nextInt(40_000));
        boolean discounted = (listingOrdinal + shopNumber) % 4 != 0;
        BigDecimal selling = discounted
                ? base.multiply(BigDecimal.valueOf(75 + random.nextInt(21)))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                : base;
        BigDecimal mrp = discounted ? base : base;
        BigDecimal cost = selling.multiply(BigDecimal.valueOf(65 + random.nextInt(16)))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        int stock = mode == CommerceMode.ONLINE_PURCHASE && (listingOrdinal + shopNumber) % 7 == 0
                ? 0 : 1 + random.nextInt(40);
        ListingPriceMode priceMode = switch (mode) {
            case ONLINE_PURCHASE -> ListingPriceMode.EXACT_PRICE;
            case VISIT_TO_BUY -> (listingOrdinal + shopNumber) % 3 == 0
                    ? ListingPriceMode.PRICE_RANGE : ListingPriceMode.EXACT_PRICE;
            case SERVICE_AT_SHOP -> ListingPriceMode.STARTING_FROM;
        };
        BigDecimal priceMax = priceMode == ListingPriceMode.PRICE_RANGE
                ? selling.add(BigDecimal.valueOf(250 + random.nextInt(7500))) : null;
        OfflineAvailability offline = mode == CommerceMode.ONLINE_PURCHASE ? null
                : OfflineAvailability.valueOf(OFFLINE_AVAILABILITY[(listingOrdinal + shopNumber) % OFFLINE_AVAILABILITY.length]);
        int duration = mode == CommerceMode.SERVICE_AT_SHOP ? 15 + ((listingOrdinal * 13) % 120) : 0;

        return new ListingSpec(shopNumber, shopCode, shopName, productName, brand,
                type.category, type.category + " essentials", description, sku, pack,
                mode == CommerceMode.SERVICE_AT_SHOP ? 1.0d : quantity(pack),
                mode == CommerceMode.SERVICE_AT_SHOP ? "service" : unit(pack),
                selling, mrp, cost, stock,
                (listingOrdinal + shopNumber) % 10 == 0,
                (listingOrdinal + shopNumber) % 7 == 0,
                mode, priceMode, priceMax, offline, duration);
    }

    private static double quantity(String pack) {
        if (pack.endsWith("kg")) return Double.parseDouble(pack.replace(" kg", ""));
        if (pack.endsWith("g")) return Double.parseDouble(pack.replace(" g", "")) / 1000.0d;
        if (pack.endsWith("L")) return Double.parseDouble(pack.replace(" L", ""));
        if (pack.endsWith("ml")) return Double.parseDouble(pack.replace(" ml", "")) / 1000.0d;
        return 1.0d;
    }

    private static String unit(String pack) {
        if (pack.endsWith("kg") || pack.endsWith("g")) return "kg";
        if (pack.endsWith("L") || pack.endsWith("ml")) return "L";
        return "piece";
    }

    private record ShopType(int shopCount, String label, String category,
                            int buyOnline, int visitToBuy,
                            int service, String products) { }
}
