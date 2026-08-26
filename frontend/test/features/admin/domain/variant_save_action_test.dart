import 'package:flutter_test/flutter_test.dart';
import 'package:gpstore/features/admin/domain/variant_save_action.dart';

void main() {
  test('the first save of a new variant creates a row', () {
    expect(
      variantSaveAction(isEditing: false, createdVariantId: null),
      VariantSaveAction.create,
    );
  });

  test('a retry after create succeeded updates instead of duplicating', () {
    expect(
      variantSaveAction(isEditing: false, createdVariantId: 42),
      VariantSaveAction.update,
    );
  });

  test('editing an existing variant always updates', () {
    expect(
      variantSaveAction(isEditing: true, createdVariantId: null),
      VariantSaveAction.update,
    );
  });
}
