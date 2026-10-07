import importlib.util
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("nnois_helper", Path(__file__).resolve().parents[1] / "nnois.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class HelperTest(unittest.TestCase):
    def test_new_clone_avoids_windows_incompatible_paths(self):
        data = Path("example-omw").resolve()
        with patch.object(helper, "executable", return_value="git"), \
                patch.object(helper, "run") as run, \
                patch.object(Path, "exists", side_effect=[False, True]):
            helper.prepare_data(data, fetch=True)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertIn("--no-checkout", commands[0])
        self.assertIn("!/wns/wikt/", commands[1])
        self.assertEqual(commands[2][-3:], ["read-tree", "-mu", "HEAD"])

    def test_repair_reuses_download_and_scopes_git_setting(self):
        with patch.object(helper, "executable", return_value="git"), \
                patch.object(helper, "run") as run, \
                patch.object(Path, "exists", return_value=True):
            helper.prepare_data(Path("example-omw"), repair=True)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(len(commands), 2)
        self.assertTrue(all(command[1:3] == ["-c", "core.protectNTFS=false"]
                            for command in commands))
        self.assertFalse(any("clone" in command for command in commands))

    def test_build_installs_multilingual_data_and_attribution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for language, lemma in (("eng", "tree"), ("ita", "albero")):
                source = root / "data/wns" / language
                source.mkdir(parents=True)
                (source / f"wn-data-{language}.tab").write_text(
                    "# Test WordNet\t1\thttps://example.org\tCC0\n"
                    f"00001740-n\t{language}:lemma\t{lemma}\n"
                    f"00001740-n\t{language}:def\ta woody plant\n", encoding="utf-8")
            output = root / "resources with spaces"
            helper.build_database(root / "data", destination=output)
            with closing(sqlite3.connect(output / "omw_multilingual.db")) as connection:
                self.assertEqual(connection.execute(
                    "SELECT lang, lemma FROM synset_lemmas ORDER BY lang").fetchall(),
                    [("eng", "tree"), ("ita", "albero")])
            credits = (output / "WORDNET_SOURCES.md").read_text(encoding="utf-8")
            self.assertIn("CC0", credits)
            # Failed rebuild must preserve the previous database.
            before = (output / "omw_multilingual.db").read_bytes()
            (root / "empty").mkdir()
            with self.assertRaises(RuntimeError):
                helper.build_database(root / "empty", destination=output)
            self.assertEqual(before, (output / "omw_multilingual.db").read_bytes())
            self.assertFalse(list(output.glob("omw-build-*")))

    def test_start_uses_built_runtime_classpath_and_module_directory(self):
        with patch.object(helper, "app_tools", return_value=("java", "mvn")), \
                patch.object(Path, "read_text", return_value="dependency.jar"), \
                patch.object(helper, "run") as run:
            helper.start_app()
        self.assertEqual(run.call_args_list[0].args[0][1], "compile")
        self.assertEqual(run.call_args_list[1].args[0][-1], "com.nnois.App")
        self.assertEqual(run.call_args_list[1].args[1], helper.MODULE)

    def test_default_run_builds_missing_database_without_flags(self):
        with patch.object(helper, "app_tools"), \
                patch.object(Path, "is_file", return_value=False), \
                patch.object(helper, "build_database") as build, \
                patch.object(helper, "start_app") as start:
            self.assertEqual(helper.main([]), 0)
        build.assert_called_once_with(helper.ROOT / "omw-data")
        start.assert_called_once_with(None)

    def test_default_run_reuses_installed_database(self):
        with patch.object(helper, "app_tools"), \
                patch.object(Path, "is_file", return_value=True), \
                patch.object(helper, "build_database") as build, \
                patch.object(helper, "start_app") as start:
            self.assertEqual(helper.main([]), 0)
        build.assert_not_called()
        start.assert_called_once_with(None)

    def test_database_only_does_not_require_java(self):
        with patch.object(helper, "app_tools") as tools, \
                patch.object(helper, "build_database") as build, \
                patch.object(helper, "start_app") as start:
            self.assertEqual(helper.main(["build-db"]), 0)
        tools.assert_not_called()
        build.assert_called_once()
        start.assert_not_called()

    def test_demo_reuses_database_and_passes_language(self):
        with patch.object(helper, "app_tools"), \
                patch.object(Path, "is_file", return_value=True), \
                patch.object(helper, "build_database") as build, \
                patch.object(helper, "start_app") as start:
            self.assertEqual(helper.main(["demo", "--language", "it"]), 0)
        build.assert_not_called()
        start.assert_called_once_with(None, ["--demo", "it"])


if __name__ == "__main__":
    unittest.main()
