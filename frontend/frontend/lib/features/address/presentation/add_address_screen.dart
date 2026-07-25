import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';

import '../../../core/theme/app_theme.dart';
import '../domain/address_models.dart';
import 'address_providers.dart';

class AddAddressScreen extends ConsumerStatefulWidget {
  const AddAddressScreen({super.key});

  @override
  ConsumerState<AddAddressScreen> createState() => _AddAddressScreenState();
}

class _AddAddressScreenState extends ConsumerState<AddAddressScreen> {
  final _formKey = GlobalKey<FormState>();
  final _fullNameController = TextEditingController();
  final _mobileController = TextEditingController();
  final _houseNoController = TextEditingController();
  final _areaController = TextEditingController();
  final _landmarkController = TextEditingController();
  final _cityController = TextEditingController();
  final _stateController = TextEditingController();
  final _pincodeController = TextEditingController();

  double? _latitude;
  double? _longitude;
  bool _isFetchingLocation = false;
  bool _isSaving = false;

  @override
  void dispose() {
    _fullNameController.dispose();
    _mobileController.dispose();
    _houseNoController.dispose();
    _areaController.dispose();
    _landmarkController.dispose();
    _cityController.dispose();
    _stateController.dispose();
    _pincodeController.dispose();
    super.dispose();
  }

  /// Real device GPS - the backend's deliverability/distance calculation
  /// needs actual coordinates, not a guess. A full interactive map picker
  /// (drag a pin) would be the eventual upgrade here; this is the genuine,
  /// working version for now, not a placeholder.
  Future<void> _useMyLocation() async {
    setState(() => _isFetchingLocation = true);

    try {
      bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        throw Exception('Please turn on location services');
      }

      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) {
          throw Exception('Location permission denied');
        }
      }
      if (permission == LocationPermission.deniedForever) {
        throw Exception('Location permission permanently denied - enable it in app settings');
      }

      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.high),
      );

      if (!mounted) return;
      setState(() {
        _latitude = position.latitude;
        _longitude = position.longitude;
      });
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Location captured')));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString().replaceFirst('Exception: ', ''))));
    } finally {
      if (mounted) setState(() => _isFetchingLocation = false);
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;

    if (_latitude == null || _longitude == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please capture your location - required for delivery')),
      );
      return;
    }

    setState(() => _isSaving = true);

    try {
      await ref.read(addressRepositoryProvider).createAddress(AddressModel(
            fullName: _fullNameController.text.trim(),
            mobileNumber: _mobileController.text.trim(),
            houseNo: _houseNoController.text.trim(),
            area: _areaController.text.trim(),
            landmark: _landmarkController.text.trim().isEmpty ? null : _landmarkController.text.trim(),
            city: _cityController.text.trim(),
            state: _stateController.text.trim(),
            pincode: _pincodeController.text.trim(),
            latitude: _latitude!,
            longitude: _longitude!,
          ));

      ref.invalidate(myAddressesProvider);
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Couldn't save address - please try again")),
      );
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Add Address')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                OutlinedButton.icon(
                  onPressed: _isFetchingLocation ? null : _useMyLocation,
                  icon: _isFetchingLocation
                      ? const SizedBox(height: 16, width: 16, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.my_location),
                  label: Text(
                    _latitude != null ? 'Location captured ✓' : 'Use my current location',
                  ),
                ),
                const SizedBox(height: 16),
                _field(_fullNameController, 'Full name'),
                _field(_mobileController, 'Mobile number', keyboardType: TextInputType.phone),
                _field(_houseNoController, 'House / Flat No.'),
                _field(_areaController, 'Area / Street'),
                _field(_landmarkController, 'Landmark (optional)', required: false),
                _field(_cityController, 'City'),
                _field(_stateController, 'State'),
                _field(_pincodeController, 'Pincode', keyboardType: TextInputType.number),
                const SizedBox(height: 24),
                FilledButton(
                  onPressed: _isSaving ? null : _save,
                  child: _isSaving
                      ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : const Text('Save Address'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _field(TextEditingController controller, String label,
      {TextInputType? keyboardType, bool required = true}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextFormField(
        controller: controller,
        keyboardType: keyboardType,
        decoration: InputDecoration(labelText: label),
        validator: required
            ? (value) => (value == null || value.trim().isEmpty) ? '$label is required' : null
            : null,
      ),
    );
  }
}
