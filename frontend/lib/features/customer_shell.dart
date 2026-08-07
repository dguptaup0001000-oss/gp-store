import 'package:flutter/material.dart';

import '../core/theme/app_theme.dart';
import 'categories/presentation/categories_screen.dart';
import 'home/presentation/home_screen.dart';
import 'orders/presentation/order_history_screen.dart';
import 'profile/presentation/profile_screen.dart';

/// Bottom-nav shell for logged-in customers, wrapping the four primary
/// destinations. Uses IndexedStack (not a fresh push per tab) so each
/// screen's scroll position and already-fetched provider data survive
/// switching tabs back and forth.
class CustomerShell extends StatefulWidget {
  const CustomerShell({super.key});

  @override
  State<CustomerShell> createState() => _CustomerShellState();
}

class _CustomerShellState extends State<CustomerShell> {
  int _selectedIndex = 0;

  static const _screens = [
    HomeScreen(),
    CategoriesScreen(),
    OrderHistoryScreen(),
    ProfileScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(index: _selectedIndex, children: _screens),
      bottomNavigationBar: BottomNavigationBar(
        currentIndex: _selectedIndex,
        onTap: (index) => setState(() => _selectedIndex = index),
        type: BottomNavigationBarType.fixed,
        selectedItemColor: AppColors.primary,
        unselectedItemColor: AppColors.textSecondary,
        items: const [
          BottomNavigationBarItem(icon: Icon(Icons.home_outlined), activeIcon: Icon(Icons.home), label: 'Home'),
          BottomNavigationBarItem(icon: Icon(Icons.category_outlined), activeIcon: Icon(Icons.category), label: 'Categories'),
          BottomNavigationBarItem(icon: Icon(Icons.receipt_long_outlined), activeIcon: Icon(Icons.receipt_long), label: 'Orders'),
          BottomNavigationBarItem(icon: Icon(Icons.person_outline), activeIcon: Icon(Icons.person), label: 'Profile'),
        ],
      ),
    );
  }
}
