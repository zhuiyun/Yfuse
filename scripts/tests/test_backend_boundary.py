"""Exercise architecture violations without treating user media hosts as a firewall rule."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('backend_boundary', ROOT / 'scripts/check-backend-boundary.py')
BOUNDARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BOUNDARY)


class BackendBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.write('yfuseBackendClient/build.gradle.kts', 'api(project(":watchTogetherProtocol"))')
        self.write('yfuseBackendClient/src/commonMain/kotlin/com/yfuse/backend/BackendEndpoints.kt',
                   'package com.yfuse.backend\nobject BackendEndpoints { const val ORIGIN = "https://backend.example" }')

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def check(self):
        return BOUNDARY.violations(self.root)

    def test_missing_backend_is_not_silently_accepted(self):
        with tempfile.TemporaryDirectory() as empty:
            self.assertIn('sources are missing', BOUNDARY.violations(Path(empty))[0])

    def test_backend_must_not_import_application_state_or_engine(self):
        self.write('yfuseBackendClient/src/commonMain/kotlin/com/yfuse/backend/Bad.kt',
                   'package com.yfuse.backend\nimport com.yfuse.app.ProductSession\n'
                   'import com.russhwolf.settings.Settings\nval engine = embyHttpEngine()')
        self.assertTrue(any('application state' in error for error in self.check()))
        self.assertTrue(any('application/platform dependency' in error for error in self.check()))

    def test_platform_backend_sources_cannot_import_application_either(self):
        self.write('yfuseBackendClient/src/jvmMain/kotlin/com/yfuse/backend/Bad.kt',
                   'package com.yfuse.backend\nimport com.yfuse.app.ProductSession')
        self.assertTrue(any('application state' in error for error in self.check()))

    def test_backend_cannot_depend_on_phone_module(self):
        self.write('yfuseBackendClient/build.gradle.kts', 'implementation(project(":phoneShared"))')
        self.assertTrue(any('forbidden project dependency :phoneShared' in error for error in self.check()))

    def test_backend_can_import_its_own_moved_models(self):
        self.write('yfuseBackendClient/src/commonMain/kotlin/com/yfuse/core/account/Models.kt',
                   'package com.yfuse.core.account\ndata class Token(val value: String)')
        self.write('yfuseBackendClient/src/commonMain/kotlin/com/yfuse/backend/Api.kt',
                   'package com.yfuse.backend\nimport com.yfuse.core.account.Token\n'
                   'import com.yfuse.watch.protocol.WatchWireMessage')
        self.assertEqual([], self.check())

    def test_backend_cannot_smuggle_an_application_type_from_a_shared_package(self):
        self.write('yfuseBackendClient/src/commonMain/kotlin/com/yfuse/backend/Api.kt',
                   'package com.yfuse.backend\nimport com.yfuse.core.account.AccountRepository')
        self.assertTrue(any('outside backend/protocol' in error for error in self.check()))

    def test_application_cannot_share_backend_package_and_filename(self):
        self.write('yfuseBackendClient/src/commonMain/kotlin/com/yfuse/core/account/AccountModels.kt',
                   'package com.yfuse.core.account\nconst val ACCOUNT_BASE_URL = "test"')
        for directory in BOUNDARY.APP_SOURCE_ROOTS:
            with self.subTest(directory=directory):
                relative = directory / 'com/yfuse/core/account/AccountModels.kt'
                self.write(relative, 'package com.yfuse.core.account\ninterface LocalAccountState')
                errors = self.check()
                self.assertTrue(any('JVM facade collision' in error for error in errors))
                self.assertTrue(any(relative.as_posix() in error for error in errors))
                (self.root / relative).unlink()

    def test_same_filename_in_another_package_is_not_a_facade_collision(self):
        self.write('yfuseBackendClient/src/commonMain/kotlin/com/yfuse/core/account/Models.kt',
                   'package com.yfuse.core.account\ninterface BackendModel')
        self.write('composeApp/src/commonMain/kotlin/com/yfuse/core/media/Models.kt',
                   'package com.yfuse.core.media\ninterface MediaModel')
        self.assertEqual([], self.check())

    def test_application_cannot_hardcode_deployment_or_owned_route(self):
        self.write('composeApp/src/commonMain/kotlin/com/yfuse/feature/NewApi.kt',
                   'val origin = "https://backend.example"\nval route = "/api/v1/account/profile"')
        self.assertTrue(any('deployment address' in error for error in self.check()))
        self.assertTrue(any('service route' in error for error in self.check()))

    def test_application_cannot_open_owned_socket_even_without_a_literal_url(self):
        self.write('composeApp/src/commonMain/kotlin/com/yfuse/core/sync/WatchTogetherClient.kt',
                   'client.webSocket(endpoint) { }')
        self.assertTrue(any('delegate transport' in error for error in self.check()))

    def test_endpoint_constant_does_not_allow_a_new_transport_in_another_file(self):
        self.write('composeApp/src/androidMain/kotlin/com/yfuse/feature/NewTransport.kt',
                   'val url = BackendEndpoints.UPDATE_MANIFEST\nURL(url).openConnection()')
        self.assertTrue(any('delegate transport' in error for error in self.check()))

    def test_third_party_media_and_platform_engine_adapters_remain_allowed(self):
        self.write('composeApp/src/commonMain/kotlin/com/yfuse/core/trakt/TraktApi.kt',
                   'import io.ktor.client.request.prepareGet\nclient.prepareGet("https://api.trakt.tv/sync/history")')
        self.write('composeApp/src/commonMain/kotlin/com/yfuse/core/network/EmbyApi.kt',
                   'import io.ktor.client.request.get\nclient.get(userSuppliedMediaUrl)')
        self.write('composeApp/src/commonMain/kotlin/com/yfuse/core/account/AccountClientFactory.kt',
                   'fun createAccountClient() = createBackendAccountClient(embyHttpEngine())')
        self.assertEqual([], self.check())

    def test_comments_and_test_fixtures_do_not_trigger_production_address_rules(self):
        self.write('composeApp/src/commonMain/kotlin/com/yfuse/feature/Note.kt',
                   '// Historic endpoint https://backend.example/api/v1/account/profile\n'
                   '/* old client.webSocket(endpoint) */\nval enabled = false')
        self.write('composeApp/src/commonTest/kotlin/com/yfuse/MediaHostTest.kt',
                   'val mediaHost = "https://backend.example"')
        self.assertEqual([], self.check())


if __name__ == '__main__':
    unittest.main()
