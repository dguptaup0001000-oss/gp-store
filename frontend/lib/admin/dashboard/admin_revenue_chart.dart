import 'package:flutter/material.dart';

import '../../features/admin/domain/analytics_models.dart';
import '../design/admin_format.dart';
import '../design/admin_tokens.dart';

/// Daily revenue, drawn with a CustomPainter.
///
/// NO CHARTING PACKAGE. fl_chart and its peers are several hundred kilobytes
/// of APK and a dependency to keep current, for one area chart with no
/// interaction. This is about a hundred lines and paints in a single pass.
///
/// The series arrives gap-filled from the backend - every day in the window
/// is present, including days nothing sold - so this walks the list
/// positionally and never has to reason about dates. See
/// AnalyticsService.getSalesSeries.
class AdminRevenueChart extends StatelessWidget {
  const AdminRevenueChart({super.key, required this.points, this.height = 160});

  final List<SalesPoint> points;
  final double height;

  @override
  Widget build(BuildContext context) {
    if (points.isEmpty) {
      return SizedBox(
        height: height,
        child: const Center(
          child: Text('No sales in this period', style: AdminText.bodyMuted),
        ),
      );
    }

    var peak = 0.0;
    for (final point in points) {
      if (point.revenue > peak) peak = point.revenue;
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              peak == 0 ? '' : AdminFormat.rupeesCompact(peak),
              style: AdminText.caption,
            ),
            const Text('peak day', style: AdminText.caption),
          ],
        ),
        const SizedBox(height: AdminSpacing.sm),
        SizedBox(
          height: height,
          child: RepaintBoundary(
            child: CustomPaint(
              painter: _RevenuePainter(points: points, peak: peak),
              size: Size.infinite,
            ),
          ),
        ),
        const SizedBox(height: AdminSpacing.sm),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(AdminFormat.shortDay(points.first.day),
                style: AdminText.caption),
            if (points.length > 2)
              Text(AdminFormat.shortDay(points[points.length ~/ 2].day),
                  style: AdminText.caption),
            Text(AdminFormat.shortDay(points.last.day),
                style: AdminText.caption),
          ],
        ),
      ],
    );
  }
}

class _RevenuePainter extends CustomPainter {
  _RevenuePainter({required this.points, required this.peak});

  final List<SalesPoint> points;
  final double peak;

  @override
  void paint(Canvas canvas, Size size) {
    if (size.width <= 0 || size.height <= 0) return;

    // Four faint gridlines. Drawn first so the series sits on top of them.
    final grid = Paint()
      ..color = AdminColors.border
      ..strokeWidth = 1;
    for (var i = 0; i <= 3; i++) {
      final y = size.height * i / 3;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), grid);
    }

    // A flat-zero window still draws a baseline rather than dividing by
    // zero: a shop with no sales this month must see an empty chart, not a
    // blank panel that looks broken.
    if (peak <= 0) {
      final baseline = Paint()
        ..color = AdminColors.borderStrong
        ..strokeWidth = 1.5;
      canvas.drawLine(Offset(0, size.height),
          Offset(size.width, size.height), baseline);
      return;
    }

    final dx = points.length == 1 ? 0.0 : size.width / (points.length - 1);
    Offset at(int index) {
      final x = points.length == 1 ? size.width / 2 : dx * index;
      // 6px of headroom so the peak is not clipped by the top edge.
      final usable = size.height - 6;
      final y = size.height - (points[index].revenue / peak) * usable;
      return Offset(x, y);
    }

    final line = Path()..moveTo(at(0).dx, at(0).dy);
    for (var i = 1; i < points.length; i++) {
      final p = at(i);
      line.lineTo(p.dx, p.dy);
    }

    final fill = Path.from(line)
      ..lineTo(at(points.length - 1).dx, size.height)
      ..lineTo(at(0).dx, size.height)
      ..close();

    canvas.drawPath(
      fill,
      Paint()
        ..shader = const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x3316A34A), Color(0x0016A34A)],
        ).createShader(Offset.zero & size),
    );

    canvas.drawPath(
      line,
      Paint()
        ..color = AdminColors.primary
        ..strokeWidth = 2
        ..style = PaintingStyle.stroke
        ..strokeJoin = StrokeJoin.round
        ..strokeCap = StrokeCap.round,
    );

    // Today gets a dot. It is the number the operator actually came to look
    // at, and on a 30-day line the last point is otherwise indistinguishable.
    final last = at(points.length - 1);
    canvas.drawCircle(last, 4, Paint()..color = AdminColors.surface);
    canvas.drawCircle(
      last,
      4,
      Paint()
        ..color = AdminColors.primary
        ..strokeWidth = 2
        ..style = PaintingStyle.stroke,
    );
  }

  @override
  bool shouldRepaint(_RevenuePainter old) =>
      old.peak != peak || !identical(old.points, points);
}
