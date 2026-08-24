import 'package:flutter/material.dart';

import '../../../core/theme/app_theme.dart';
import 'admin_analytics_screen.dart';
import 'admin_audit_log_screen.dart';
import 'admin_broadcast_screen.dart';
import 'admin_category_list_screen.dart';
import 'admin_coupon_list_screen.dart';
import 'admin_customers_screen.dart';
import 'admin_delivery_breaches_screen.dart';
import 'admin_delivery_partners_screen.dart';
import 'admin_delivery_pricing_screen.dart';
import 'admin_inventory_screen.dart';
import 'admin_order_list_screen.dart';
import 'admin_payments_screen.dart';
import 'admin_printer_settings_screen.dart';
import 'admin_product_list_screen.dart';
import 'admin_reviews_screen.dart';
import 'admin_voice_settings_screen.dart';
import '../../../core/util/haptic_widgets.dart';

/// Only reachable from ProfileScreen when the logged-in customer's role is
/// ADMIN - see profile_screen.dart's role check.
class AdminHomeScreen extends StatelessWidget {
  const AdminHomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Store Management')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _tile(
            context,
            icon: Icons.receipt_long_outlined,
            title: 'All Orders',
            subtitle: 'View and manage every order',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminOrderListScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.payments_outlined,
            title: 'Payments',
            subtitle: 'Confirm UPI, process refunds',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminPaymentsScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.campaign_outlined,
            title: 'Broadcast Notification',
            subtitle: 'Send an announcement to every customer',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminBroadcastScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.analytics_outlined,
            title: 'Analytics',
            subtitle: 'Sales, top products, order status',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminAnalyticsScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.inventory_2_outlined,
            title: 'Products',
            subtitle: 'Add, edit, and manage stock',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminProductListScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.category_outlined,
            title: 'Categories',
            subtitle: 'Organize your product catalog',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminCategoryListScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.inventory_outlined,
            title: 'Inventory',
            subtitle: 'Stock levels, restock, low-stock alerts',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminInventoryScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.local_offer_outlined,
            title: 'Coupons',
            subtitle: 'Create and manage discount offers',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminCouponListScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.delivery_dining_outlined,
            title: 'Delivery Partners',
            subtitle: 'Manage your delivery team and availability',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminDeliveryPartnersScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.local_shipping_outlined,
            title: 'Delivery Pricing',
            subtitle: 'Distance, weight, and free-delivery rules',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminDeliveryPricingScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.report_problem_outlined,
            title: 'Delivery Breaches',
            subtitle: 'Orders that missed their promised delivery time',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminDeliveryBreachesScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.history_outlined,
            title: 'Audit Log',
            subtitle: 'Full history of important actions',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminAuditLogScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.people_outline,
            title: 'Customers',
            subtitle: 'View and manage customer accounts',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminCustomersScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.rate_review_outlined,
            title: 'Review Moderation',
            subtitle: 'Remove inappropriate reviews',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminReviewsScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.print_outlined,
            title: 'Receipt Printer',
            subtitle: 'Connect a printer to auto-print new orders',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminPrinterSettingsScreen()),
            )),
          ),
          _tile(
            context,
            icon: Icons.campaign_outlined,
            title: 'Order Announcements',
            subtitle: 'Speak new orders aloud, like a payment soundbox',
            onTap: hapticize(() => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const AdminVoiceSettingsScreen()),
            )),
          ),
        ],
      ),
    );
  }

  Widget _tile(BuildContext context,
      {required IconData icon, required String title, required String subtitle, required VoidCallback onTap}) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(color: AppColors.cardBackground, borderRadius: BorderRadius.circular(12)),
      child: ListTile(
        leading: Icon(icon, color: AppColors.primary),
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
        subtitle: Text(subtitle),
        trailing: const Icon(Icons.chevron_right),
        onTap: onTap,
      ),
    );
  }
}
