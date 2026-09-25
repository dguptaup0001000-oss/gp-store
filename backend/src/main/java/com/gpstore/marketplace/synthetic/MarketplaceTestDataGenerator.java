package com.gpstore.marketplace.synthetic;

import com.gpstore.catalog.shop.CommerceMode;
import com.gpstore.catalog.shop.ListingPriceMode;
import com.gpstore.catalog.shop.OfflineAvailability;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic, image-free specifications for the controlled 100-shop test batch. */
public final class MarketplaceTestDataGenerator {

    public static final String BATCH_ID = "MARKETPLACE_TEST_100_SHOPS_V1";
    public static final int SHOP_COUNT = 100;
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
            new ShopType("Kirana Grocery", "Grocery", 60, 0, 0,
                    "Iodised Salt|Whole Wheat Atta|Basmati Rice|Toor Dal|Turmeric Powder|Mustard Oil|Tea Leaves|Digestive Biscuits|Bathing Soap|Dishwash Liquid|Detergent Powder|Poha|Jaggery|Chana|Groundnut Oil"),
            new ShopType("Neighbourhood Supermarket", "Supermarket", 55, 5, 0,
                    "Packaged Pasta|Breakfast Oats|Corn Flakes|Tomato Ketchup|Fruit Juice|Chocolate Bar|Paper Towels|Aluminium Foil|Floor Cleaner|Hand Wash|Instant Noodles|Peanut Butter|Green Tea|Laundry Liquid"),
            new ShopType("Mobile Phone Store", "Mobile and Electronics", 18, 32, 10,
                    "USB-C Cable|Fast Charger|Phone Case|Tempered Glass|Wireless Earbuds|Power Bank|Smartphone|Feature Phone|Bluetooth Speaker|Screen Guard|Mobile Screen Repair|Battery Replacement|Charging Port Repair"),
            new ShopType("Electronics Shop", "Electronics", 20, 30, 10,
                    "HDMI Cable|Universal Remote|LED Television|Bluetooth Speaker|Wi-Fi Router|Smart Plug|Extension Board|Television Repair|Speaker Repair|Remote Setup"),
            new ShopType("Hardware Store", "Hardware", 30, 28, 2,
                    "Screw Assortment|Wall Plug Set|Door Hinge|Padlock|Paint Brush|Measuring Tape|Hand Saw|Steel Pipe|Water Tank|Drill Machine|Door Fitting Service"),
            new ShopType("Clothing Boutique", "Clothing", 28, 32, 0,
                    "Cotton Kurta|Casual Shirt|Printed Saree|Leggings|Kids T-Shirt|School Uniform|Denim Jeans|Dupatta|Night Suit|Blouse Piece|Clothing Alteration"),
            new ShopType("Footwear Store", "Footwear", 28, 32, 0,
                    "Walking Shoes|School Shoes|Leather Sandals|Rubber Slippers|Sports Socks|Rain Boots|Canvas Shoes|Shoe Care Kit|Shoe Repair Service"),
            new ShopType("Home Appliance Store", "Home Appliances", 20, 30, 10,
                    "Ceiling Fan|Mixer Grinder|Refrigerator|Washing Machine|Air Cooler|Induction Cooktop|Electric Kettle|AC Service|Appliance Inspection"),
            new ShopType("Furniture House", "Furniture", 22, 38, 0,
                    "Study Table|Dining Chair|Bookshelf|Single Bed|Sofa Set|Mattress|Wardrobe|Coffee Table|Furniture Assembly|Furniture Repair"),
            new ShopType("Kitchenware Shop", "Kitchenware", 45, 15, 0,
                    "Steel Plate Set|Pressure Cooker|Nonstick Pan|Storage Container|Water Bottle|Knife Set|Lunch Box|Gas Lighter|Kitchen Rack|Cookware Polish"),
            new ShopType("Beauty and Cosmetics", "Beauty and Personal Care", 48, 12, 0,
                    "Face Wash|Moisturising Lotion|Hair Oil|Shampoo|Conditioner|Lip Balm|Kajal Pencil|Nail Polish|Sunscreen|Comb Set"),
            new ShopType("Stationery Mart", "Stationery", 56, 4, 0,
                    "Notebook|Ball Pen Set|Geometry Box|Drawing Colours|Printer Paper|School Bag|Stapler|Desk Organiser|Marker Set|Art Paper"),
            new ShopType("Local Bakery", "Bakery and Food", 60, 0, 0,
                    "Milk Bread|Brown Bread|Rusk|Butter Cookies|Fruit Cake|Cupcake Box|Pav Buns|Khari Biscuits|Tea Cake|Whole Wheat Loaf"),
            new ShopType("Family Restaurant", "Restaurant and Food", 58, 2, 0,
                    "Veg Thali|Paneer Curry|Dal Tadka|Jeera Rice|Tandoori Roti|Veg Biryani|Masala Dosa|Chole Bhature|Lassi|Gulab Jamun"),
            new ShopType("Medical Supplies Test Shop", "Medical Test Supplies", 52, 8, 0,
                    "Digital Thermometer|Reusable Hot Water Bag|First Aid Box|Cotton Roll|Crepe Bandage|Adult Walking Stick|Pill Organiser|Digital Weighing Scale|Surgical Mask Pack|Hand Sanitiser"),
            new ShopType("Auto Parts Centre", "Automotive Parts", 34, 26, 0,
                    "Wiper Blade Set|Car Air Filter|Engine Oil Can|Car Floor Mat|Headlamp Bulb|Battery Cable|Tyre Inflator|Seat Cover Set|Car Accessories Fitment"),
            new ShopType("Bike Parts Bazaar", "Motorcycle Parts", 34, 24, 2,
                    "Brake Shoe Set|Motorcycle Mirror|Chain Lubricant|Helmet|Tubeless Puncture Kit|Clutch Cable|Indicator Lamp|Bike Service Check"),
            new ShopType("Agriculture Supply House", "Agriculture Supplies", 48, 12, 0,
                    "Garden Hose|Hand Sprayer|Seed Tray|Watering Can|Pruning Shears|Organic Compost|Drip Connector|Farm Gloves|Tool Sharpening Service"),
            new ShopType("Electrical Goods Store", "Electrical Supplies", 38, 20, 2,
                    "LED Bulb|Switch Board|Extension Cord|Ceiling Fan Regulator|Copper Wire Coil|MCB Switch|Tube Light|Electrical Repair Visit"),
            new ShopType("Computer Shop", "Computers and Accessories", 22, 28, 10,
                    "Wireless Mouse|Keyboard|Laptop Stand|USB Drive|Wi-Fi Adapter|Laptop|Desktop Computer|Printer|Laptop Repair|Printer Repair"),
            new ShopType("Gift and Decor House", "Gifts and Home Decor", 32, 28, 0,
                    "Ceramic Mug Set|Photo Frame|Desk Lamp|Wall Clock|Gift Hamper|Decorative Vase|Festival Lights|Greeting Card Pack|Name Plate"),
            new ShopType("Toy and Play Store", "Toys and Kids", 42, 18, 0,
                    "Building Block Set|Soft Toy|Jigsaw Puzzle|Remote Car|Colouring Book|Board Game|Baby Rattle|Outdoor Ball|Learning Flash Cards"),
            new ShopType("Home Services Desk", "Home Services", 8, 2, 50,
                    "Tap Washer Set|Door Lock Set|LED Bulb Pair|Plumbing Inspection|Tap Repair|Furniture Assembly|Home Deep Cleaning|Water Filter Service|Ceiling Fan Installation"),
            new ShopType("Mobile Repair Studio", "Repair Services", 8, 2, 50,
                    "Charging Cable|Phone Case|Screen Protector|Mobile Screen Repair|Battery Replacement|Charging Port Repair|Speaker Repair|Water Damage Inspection|Software Setup"),
            new ShopType("Appliance Service Centre", "Repair Services", 8, 2, 50,
                    "Power Cord|Water Inlet Pipe|AC Servicing|Refrigerator Repair|Washing Machine Repair|Microwave Repair|Cooler Pump Repair|Appliance Diagnosis"),
            new ShopType("Cleaning and Care Services", "Cleaning Services", 8, 2, 50,
                    "Cleaning Brush Set|Microfibre Cloth Pack|Floor Cleaner|Bathroom Deep Cleaning|Sofa Cleaning|Kitchen Cleaning|Water Tank Cleaning|Window Cleaning")
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
        Random random = new Random(seed);
        List<ShopSpec> shops = new ArrayList<>(SHOP_COUNT);
        List<ListingSpec> listings = new ArrayList<>(LISTING_COUNT);
        BigDecimal[] radii = {
                new BigDecimal("3.00"), new BigDecimal("5.00"), new BigDecimal("8.00"),
                new BigDecimal("10.00"), new BigDecimal("15.00"), new BigDecimal("20.00"),
                new BigDecimal("30.00")
        };

        for (int shopNumber = 1; shopNumber <= SHOP_COUNT; shopNumber++) {
            int ordinal = shopNumber - 1;
            ShopType type = TYPES.get(ordinal % TYPES.size());
            String surname = SURNAMES[(ordinal * 7) % SURNAMES.length];
            String locality = LOCALITIES[(ordinal * 11) % LOCALITIES.length];
            String shopName = surname + " " + type.label + " TEST " + locality + " "
                    + String.format("%03d", shopNumber);
            String shopCode = String.format("MKT100V1-SHOP-%03d", shopNumber);
            // Keep every synthetic shop inside the production ladder's
            // default maximum (historically 25 km), while retaining enough
            // spread to exercise the nearer/farther rungs. The radius is
            // chosen from values that actually reach the generated point,
            // so valid test shops are not silently filtered from the local
            // marketplace before customers can inspect their listings.
            double distanceKm = shopNumber == 1 ? 0.25 : 0.4 + ((shopNumber * 37) % 240) / 10.0;
            double bearingRadians = Math.toRadians((shopNumber * 137.507764) % 360.0);
            List<BigDecimal> eligibleRadii = Arrays.stream(radii)
                    // Leave a small geodesic rounding margin for the
                    // repository's persisted decimal coordinates.
                    .filter(radius -> radius.doubleValue() >= distanceKm + 0.1d)
                    .toList();
            BigDecimal shopRadius = eligibleRadii.get((shopNumber - 1) % eligibleRadii.size());
            int[] counts = {type.buyOnline, type.visitToBuy, type.service};
            shops.add(new ShopSpec(shopNumber, shopName,
                    "[" + BATCH_ID + "] Merchant " + String.format("%03d", shopNumber),
                    shopCode, type.label, type.category, locality, distanceKm, bearingRadians,
                    shopRadius, counts[0], counts[1], counts[2]));

            int listingNumber = 0;
            for (int i = 0; i < type.buyOnline; i++) {
                listings.add(makeListing(random, shopNumber, shopCode, shopName, type,
                        CommerceMode.ONLINE_PURCHASE, i, listingNumber++));
            }
            for (int i = 0; i < type.visitToBuy; i++) {
                listings.add(makeListing(random, shopNumber, shopCode, shopName, type,
                        CommerceMode.VISIT_TO_BUY, i, listingNumber++));
            }
            for (int i = 0; i < type.service; i++) {
                listings.add(makeListing(random, shopNumber, shopCode, shopName, type,
                        CommerceMode.SERVICE_AT_SHOP, i, listingNumber++));
            }
        }
        return new Dataset(List.copyOf(shops), List.copyOf(listings));
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

    private record ShopType(String label, String category, int buyOnline, int visitToBuy,
                            int service, String products) { }
}
