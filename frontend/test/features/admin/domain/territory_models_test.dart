import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/territory_models.dart';

void main() {
  group('TerritoryHealth.fromJson', () {
    test('reads the 8/26 design targets and empty-map problems', () {
      final health = TerritoryHealth.fromJson({
        'zones': 0,
        'expectedZones': 8,
        'subzones': 0,
        'expectedSubzones': 26,
        'subzonesWithBoundary': 0,
        'subzonesWithPrimaryPartner': 0,
        'subzonesMissingBoundary': <String>[],
        'subzonesMissingPartner': <String>[],
        'subzonesWithNoNeighbours': <String>[],
        'problems': [
          'Expected 8 main zones, found 0.',
          'Expected 26 subzones, found 0.',
        ],
      });

      expect(health.expectedZones, 8);
      expect(health.expectedSubzones, 26);
      expect(health.hasProblems, isTrue);
      expect(health.problems, hasLength(2));
    });

    test('a finished map has no problems', () {
      const health = TerritoryHealth(
        zones: 8,
        expectedZones: 8,
        subzones: 26,
        expectedSubzones: 26,
        subzonesWithBoundary: 26,
        subzonesWithPrimaryPartner: 26,
      );
      expect(health.hasProblems, isFalse);
    });
  });

  group('describeBoundary', () {
    test('missing text is missing, not a map', () {
      expect(describeBoundary(null), BoundaryPresence.missing);
      expect(describeBoundary('  '), BoundaryPresence.missing);
    });

    test('a stored ring of at least three points is stored, not drawn', () {
      expect(
        describeBoundary('[[28.61,77.20],[28.62,77.20],[28.62,77.21]]'),
        BoundaryPresence.stored,
      );
      expect(boundaryVertexCount('[[28.61,77.20],[28.62,77.20],[28.62,77.21]]'), 3);
    });

    test('garbage JSON is unreadable rather than pretended to be a polygon', () {
      expect(describeBoundary('not-json'), BoundaryPresence.unreadable);
      expect(describeBoundary('[]'), BoundaryPresence.unreadable);
    });
  });

  group('TerritorySubzone.fromJson', () {
    test('reads a nested zone and rider without requiring a boundary', () {
      final subzone = TerritorySubzone.fromJson({
        'id': 4,
        'code': 'Z7B',
        'name': 'Canal Colony',
        'maxConcurrentOrders': 12,
        'active': true,
        'zone': {'id': 7, 'code': 'Z7', 'name': 'Canal'},
        'primaryPartner': {'id': 9, 'name': 'Ravi', 'mobile': '999', 'vehicleType': 'BIKE'},
      });

      expect(subzone.code, 'Z7B');
      expect(subzone.zone?.code, 'Z7');
      expect(subzone.primaryPartner?.name, 'Ravi');
      expect(subzone.boundaryPresence, BoundaryPresence.missing);
    });
  });

  group('TerritoryResolveResult.fromJson', () {
    test('an empty map reports no match', () {
      final result = TerritoryResolveResult.fromJson({
        'subzoneCode': '',
        'matches': <String>[],
        'overlapping': false,
        'mappedTerritories': 0,
      });
      expect(result.matches, isEmpty);
      expect(result.mappedTerritories, 0);
    });
  });
}
