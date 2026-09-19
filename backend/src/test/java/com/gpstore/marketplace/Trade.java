package com.gpstore.marketplace;

import java.util.List;

/**
 * Thirty businesses that have nothing in common but a platform.
 *
 * <h2>Why the variety is the point</h2>
 *
 * <p>Thirty copies of one kirana would prove nothing: every query would be
 * shaped the same, every category would be the same word, and the one thing
 * worth testing - that a phone shop and a saree shop and a tractor-parts
 * dealer can share one database without seeing each other - would be invisible.
 *
 * <p>So each trade here brings its OWN attribute vocabulary. A phone is told
 * apart by RAM and Storage, a shoe by Size, a saree by Material and Design, a
 * restaurant by Portion, a medicine by Strength. That is the generic variant
 * model (V71) being made to earn its keep: if any of this had to be expressed
 * as "pack size" and "unit", the model would be grocery-shaped and the test
 * would say so.
 *
 * <p>Sizes vary too, because a marketplace is not uniform. One grocery carries
 * roughly five hundred lines; a jeweller carries a dozen. Code that only works
 * when every shop is the same size fails on the first real one.
 *
 * <p>EVERY NAME SAYS TEST. Not decoration - these rows must be unmistakable if
 * one is ever seen on a screen, and greppable if one is ever seen in a
 * database.
 */
enum Trade {

    KIRANA("Grocery / Kirana", Size.HUGE,
            List.of("Atta, Rice & Dal", "Oil & Ghee", "Masala & Spices", "Biscuits & Snacks",
                    "Tea, Coffee & Beverages", "Cleaning & Household"),
            List.of("Pack size", "Unit"),
            List.of("Aashirvaad Atta", "India Gate Basmati", "Toor Dal", "Fortune Oil",
                    "Tata Salt", "Everest Garam Masala", "Parle-G", "Red Label Tea",
                    "Surf Excel", "Colgate Dental Cream", "Maggi Noodles", "Amul Butter"),
            List.of("500 g", "1 kg", "5 kg", "250 g", "2 L")),

    PHONES("Mobile Phones", Size.SMALL,
            List.of("Smartphones", "Feature Phones", "Tablets"),
            List.of("RAM", "Storage", "Colour"),
            List.of("Moto Edge", "Galaxy A", "Pixel", "Redmi Note", "iQOO Z", "Realme Narzo"),
            List.of("8 GB", "12 GB", "128 GB", "256 GB", "Black", "Red")),

    PHONE_ACCESSORIES("Mobile Accessories", Size.MEDIUM,
            List.of("Chargers", "Cases & Covers", "Screen Guards", "Earphones", "Power Banks"),
            List.of("Compatibility", "Colour", "Length"),
            List.of("USB-C Charger 65W", "Silicone Back Cover", "Tempered Glass",
                    "Wired Earphones", "10000 mAh Power Bank", "Car Mount"),
            List.of("Universal", "Type-C", "Black", "Clear", "1 m", "2 m")),

    SAREE("Saree", Size.MEDIUM,
            List.of("Silk Sarees", "Cotton Sarees", "Georgette Sarees", "Bridal"),
            List.of("Material", "Colour", "Design"),
            List.of("Banarasi Silk Saree", "Kanjivaram Saree", "Chanderi Cotton Saree",
                    "Georgette Printed Saree", "Tussar Silk Saree"),
            List.of("Silk", "Cotton", "Georgette", "Red", "Green", "Printed", "Woven")),

    MENSWEAR("Men's Clothing", Size.MEDIUM,
            List.of("Shirts", "Trousers", "T-Shirts", "Kurta"),
            List.of("Size", "Colour", "Fit"),
            List.of("Formal Shirt", "Slim Fit Trousers", "Round Neck T-Shirt", "Cotton Kurta"),
            List.of("S", "M", "L", "XL", "Blue", "White", "Slim", "Regular")),

    WOMENSWEAR("Women's Clothing", Size.MEDIUM,
            List.of("Kurtis", "Leggings", "Dresses", "Dupattas"),
            List.of("Size", "Colour", "Material"),
            List.of("Anarkali Kurti", "Ankle Leggings", "A-Line Dress", "Chiffon Dupatta"),
            List.of("S", "M", "L", "XL", "Pink", "Black", "Rayon", "Cotton")),

    SHOES("Shoes", Size.MEDIUM,
            List.of("Sports Shoes", "Formal Shoes", "Sandals", "Slippers"),
            List.of("Size", "Colour", "Style"),
            List.of("Running Shoes", "Leather Oxford", "Floater Sandal", "Rubber Slipper"),
            List.of("7", "8", "9", "10", "Black", "Brown", "Lace-up", "Slip-on")),

    PHARMACY("Pharmacy", Size.LARGE,
            List.of("Pain Relief", "Cold & Fever", "Diabetes Care", "Vitamins", "First Aid"),
            List.of("Strength", "Pack"),
            List.of("Paracetamol", "Cetirizine", "Metformin", "Vitamin D3", "Antiseptic Liquid",
                    "Crepe Bandage", "ORS Sachet"),
            List.of("500 mg", "650 mg", "10 mg", "10 tablets", "15 tablets", "100 ml")),

    RESTAURANT("Restaurant", Size.SMALL,
            List.of("Starters", "Main Course", "Breads", "Rice", "Desserts"),
            List.of("Portion"),
            List.of("Paneer Tikka", "Dal Makhani", "Butter Naan", "Jeera Rice", "Gulab Jamun"),
            List.of("Half", "Full", "Regular", "Large")),

    BIRYANI("Biryani / Fast Food", Size.MICRO,
            List.of("Biryani", "Rolls", "Burgers", "Beverages"),
            List.of("Portion", "Spice"),
            List.of("Chicken Biryani", "Veg Biryani", "Egg Roll", "Veg Burger", "Cold Coffee"),
            List.of("Half", "Full", "Regular", "Medium", "Hot")),

    BAKERY("Bakery", Size.SMALL,
            List.of("Cakes", "Pastries", "Breads", "Cookies"),
            List.of("Weight", "Flavour"),
            List.of("Chocolate Truffle Cake", "Black Forest Pastry", "Brown Bread",
                    "Butter Cookies"),
            List.of("500 g", "1 kg", "Chocolate", "Vanilla", "Strawberry")),

    PRODUCE("Fruits & Vegetables", Size.MEDIUM,
            List.of("Fresh Fruits", "Fresh Vegetables", "Leafy Greens", "Exotic"),
            List.of("Weight", "Grade"),
            List.of("Banana", "Apple Shimla", "Tomato", "Onion", "Potato", "Spinach", "Coriander"),
            List.of("500 g", "1 kg", "A", "B")),

    DAIRY("Dairy", Size.SMALL,
            List.of("Milk", "Curd & Paneer", "Butter & Cheese", "Sweets"),
            List.of("Volume", "Type"),
            List.of("Toned Milk", "Full Cream Milk", "Fresh Curd", "Paneer Block", "Cheese Slices"),
            List.of("500 ml", "1 L", "200 g", "400 g", "Toned", "Full cream")),

    ELECTRONICS("Electronics", Size.MEDIUM,
            List.of("Televisions", "Audio", "Cameras", "Smart Devices"),
            List.of("Screen size", "Colour", "Connectivity"),
            List.of("LED Television", "Bluetooth Speaker", "Action Camera", "Smart Bulb"),
            List.of("32 inch", "43 inch", "Black", "White", "Bluetooth", "Wi-Fi")),

    COMPUTERS("Computer Accessories", Size.MEDIUM,
            List.of("Keyboards & Mice", "Storage", "Monitors", "Networking", "Cables"),
            List.of("Capacity", "Interface", "Colour"),
            List.of("Wireless Mouse", "Mechanical Keyboard", "Portable SSD", "USB Hub",
                    "HDMI Cable", "Wi-Fi Router"),
            List.of("256 GB", "512 GB", "1 TB", "USB 3.0", "Type-C", "Black")),

    HARDWARE("Hardware", Size.LARGE,
            List.of("Hand Tools", "Power Tools", "Fasteners", "Plumbing", "Paint"),
            List.of("Size", "Material", "Grade"),
            List.of("Claw Hammer", "Screwdriver Set", "Hex Bolt", "PVC Pipe", "Wall Putty",
                    "Measuring Tape", "Angle Grinder"),
            List.of("6 mm", "8 mm", "12 mm", "Steel", "Brass", "PVC", "Grade A")),

    ELECTRICAL("Electrical Supplies", Size.MEDIUM,
            List.of("Wires & Cables", "Switches", "Lighting", "Fans", "MCB & Distribution"),
            List.of("Rating", "Colour", "Length"),
            List.of("House Wire 1.5 sqmm", "Modular Switch", "LED Bulb", "Ceiling Fan",
                    "MCB 16A", "Extension Board"),
            List.of("6 A", "16 A", "9 W", "12 W", "90 m", "White")),

    APPLIANCES("Home Appliances", Size.SMALL,
            List.of("Kitchen Appliances", "Cooling", "Cleaning", "Water"),
            List.of("Capacity", "Colour", "Power"),
            List.of("Mixer Grinder", "Induction Cooktop", "Table Fan", "Water Purifier",
                    "Electric Kettle"),
            List.of("1.5 L", "2 L", "750 W", "1200 W", "Grey", "White")),

    FURNITURE("Furniture", Size.SMALL,
            List.of("Seating", "Tables", "Storage", "Beds"),
            List.of("Material", "Colour", "Seats"),
            List.of("Plastic Chair", "Study Table", "Steel Almirah", "Single Bed", "Shoe Rack"),
            List.of("Plastic", "Wood", "Steel", "Brown", "Black", "1", "2")),

    COSMETICS("Cosmetics / Beauty", Size.MEDIUM,
            List.of("Skin Care", "Hair Care", "Makeup", "Fragrance"),
            List.of("Volume", "Shade", "Skin type"),
            List.of("Face Wash", "Hair Oil", "Lipstick", "Body Lotion", "Deodorant", "Kajal"),
            List.of("100 ml", "200 ml", "Maroon", "Nude", "Oily", "Dry")),

    STATIONERY("Stationery", Size.LARGE,
            List.of("Notebooks", "Pens & Pencils", "Files & Folders", "Art Supplies",
                    "Office Supplies"),
            List.of("Size", "Colour", "Pages"),
            List.of("Long Notebook", "Gel Pen", "Ball Pen", "Box File", "Colour Pencils",
                    "Stapler", "A4 Paper Ream"),
            List.of("A4", "A5", "Blue", "Black", "172 pages", "300 pages")),

    BOOKS("Books", Size.LARGE,
            List.of("School Books", "Competitive Exams", "Fiction", "Children"),
            List.of("Language", "Binding", "Class"),
            List.of("Mathematics Textbook", "General Knowledge Guide", "Hindi Novel",
                    "Picture Book", "English Grammar"),
            List.of("Hindi", "English", "Paperback", "Hardbound", "Class 8", "Class 10")),

    TOYS("Toys", Size.MEDIUM,
            List.of("Soft Toys", "Board Games", "Remote Control", "Educational"),
            List.of("Age", "Colour", "Material"),
            List.of("Teddy Bear", "Ludo Board", "RC Car", "Building Blocks", "Puzzle Set"),
            List.of("3+", "6+", "Brown", "Red", "Plastic", "Plush")),

    SPORTS("Sports", Size.MEDIUM,
            List.of("Cricket", "Fitness", "Badminton", "Football"),
            List.of("Size", "Weight", "Material"),
            List.of("Cricket Bat", "Leather Ball", "Dumbbell", "Badminton Racket", "Football",
                    "Skipping Rope"),
            List.of("SH", "Full", "2 kg", "5 kg", "Willow", "Rubber")),

    AUTO_PARTS("Automobile Parts", Size.LARGE,
            List.of("Engine Parts", "Brakes & Clutch", "Filters", "Lighting", "Batteries"),
            List.of("Vehicle", "Part number", "Material"),
            List.of("Brake Shoe Set", "Clutch Plate", "Oil Filter", "Air Filter",
                    "Headlight Assembly", "Spark Plug"),
            List.of("Splendor", "Activa", "Alto", "Swift", "GP-TEST-PN-1", "GP-TEST-PN-2")),

    TRACTOR_PARTS("Tractor / Agricultural Parts", Size.MEDIUM,
            List.of("Tractor Spares", "Implements", "Irrigation", "Farm Tools"),
            List.of("Vehicle", "Size", "Material"),
            List.of("Tractor Hydraulic Seal Kit", "Cultivator Tyne", "Sprinkler Nozzle",
                    "Plough Share", "Sprayer Pump"),
            List.of("Mahindra 575", "Swaraj 744", "12 mm", "Cast iron", "Steel")),

    PET("Pet Supplies", Size.SMALL,
            List.of("Dog Food", "Cat Food", "Pet Grooming", "Accessories"),
            List.of("Weight", "Life stage"),
            List.of("Adult Dog Food", "Puppy Food", "Cat Litter", "Pet Shampoo", "Dog Collar"),
            List.of("1 kg", "3 kg", "Adult", "Puppy")),

    JEWELLERY("Jewellery / Fashion Accessories", Size.MICRO,
            List.of("Earrings", "Bangles", "Necklaces", "Watches"),
            List.of("Material", "Colour", "Size"),
            List.of("Oxidised Earrings", "Glass Bangles Set", "Pendant Chain", "Analog Watch"),
            List.of("Oxidised", "Gold plated", "2.4", "2.6", "Silver", "Gold")),

    HOME_KITCHEN("Home & Kitchen", Size.LARGE,
            List.of("Cookware", "Storage & Containers", "Dining", "Bath", "Home Decor"),
            List.of("Capacity", "Material", "Colour"),
            List.of("Pressure Cooker", "Steel Container Set", "Dinner Plate", "Bath Towel",
                    "Wall Clock", "Casserole"),
            List.of("2 L", "3 L", "5 L", "Steel", "Aluminium", "Blue")),

    GIFTS("Gifts / Flowers", Size.MICRO,
            List.of("Bouquets", "Gift Hampers", "Greeting Cards", "Festive"),
            List.of("Occasion", "Colour", "Size"),
            List.of("Rose Bouquet", "Chocolate Hamper", "Birthday Card", "Rakhi Set"),
            List.of("Birthday", "Anniversary", "Red", "Mixed", "Small", "Large"));

    /**
     * How many lines this kind of shop carries.
     *
     * <p>Deliberately uneven. Code that quietly assumes every shop is the same
     * size - a page that fetches "all products", a screen that never paginates -
     * behaves perfectly until it meets the grocery, and these bands are what
     * make the grocery exist.
     */
    enum Size {
        MICRO(5, 20),
        SMALL(20, 75),
        MEDIUM(75, 250),
        LARGE(250, 400),
        HUGE(480, 520);

        final int min;
        final int max;

        Size(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }

    final String label;
    final Size size;
    /** What this trade calls its departments. No two lists are the same. */
    final List<String> categories;
    /** The attribute NAMES this trade tells two sellable things apart by. */
    final List<String> attributeNames;
    /** Recognisable product stems - never "Product 1". */
    final List<String> productStems;
    /** Values the attributes above draw from. */
    final List<String> attributeValues;

    Trade(String label, Size size, List<String> categories, List<String> attributeNames,
          List<String> productStems, List<String> attributeValues) {
        this.label = label;
        this.size = size;
        this.categories = categories;
        this.attributeNames = attributeNames;
        this.productStems = productStems;
        this.attributeValues = attributeValues;
    }
}
