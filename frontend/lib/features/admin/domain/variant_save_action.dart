/// Whether an admin variant form should POST a new row or PUT an existing one.
///
/// After create succeeds, a later photo-save failure must not POST again:
/// that is how duplicate variants appear in the catalogue.
enum VariantSaveAction { create, update }

VariantSaveAction variantSaveAction({
  required bool isEditing,
  int? createdVariantId,
}) {
  if (isEditing || createdVariantId != null) {
    return VariantSaveAction.update;
  }
  return VariantSaveAction.create;
}
