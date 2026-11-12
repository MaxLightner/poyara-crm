"""Dependency-light tests for the free self-hosted queue transport.
No model downloads, API keys or real audio processing during CI.
"""
import importlib
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

class VoiceQueueTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        os.environ["POYARA_DATA_DIR"] = cls.temp.name
        os.environ["POYARA_VOICE_TOKEN"] = "test-secret-" + "x"*40
        cls.api = importlib.import_module("offline_voice_api")
        cls.client = TestClient(cls.api.app)
        cls.headers = {"Authorization": "Bearer " + os.environ["POYARA_VOICE_TOKEN"]}

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_rejects_missing_pairing_code(self):
        reply=self.client.get("/v1/jobs/11111111-1111-4111-8111-111111111111")
        self.assertEqual(reply.status_code,401)

    def test_upload_and_poll_without_inference(self):
        job_id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        with patch.object(self.api.pool,"submit") as submit:
            response=self.client.post("/v1/jobs",headers={
              **self.headers,"X-Voice-Job-Id":job_id},
              files={"audio":("test.m4a",b"1234"*50,"audio/mp4")},
              data={"context_json":'[]'})
            self.assertEqual(response.status_code,202)
            self.assertEqual(response.json()["job_id"],job_id)
            self.assertEqual(submit.call_count,1)
            repeated=self.client.post("/v1/jobs",headers={
              **self.headers,"X-Voice-Job-Id":job_id},
              files={"audio":("test.m4a",b"1234"*50,"audio/mp4")})
            self.assertEqual(repeated.status_code,202)
            self.assertEqual(submit.call_count,1)
        status=self.client.get("/v1/jobs/"+job_id,headers=self.headers)
        self.assertEqual(status.status_code,200)
        self.assertEqual(status.json()["stage"],"queued")

    def test_rejects_invalid_job_id(self):
        response=self.client.post("/v1/jobs",headers={
          **self.headers,"X-Voice-Job-Id":"bad"},
          files={"audio":("test.m4a",b"1234"*50,"audio/mp4")})
        self.assertEqual(response.status_code,400)

if __name__=="__main__":
    unittest.main()
