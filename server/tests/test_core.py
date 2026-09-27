import sqlite3
import unittest

from wenfou.core import InvalidAction, NotFound, WenfouStore


class PrivacyBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.store = WenfouStore(self.db)
        self.store.initialize()
        self.cid = self.store.create_conversation("alice")
        self.question_id, self.answer_id = self.store.ask(
            "alice", self.cid, "为什么要分享问题？"
        )
        self.store.append_message(
            "alice", self.cid, "user", "我的手机号是 13800000000，不要公开"
        )

    def tearDown(self):
        self.db.close()

    def submit(self):
        return self.store.submit_post(
            "alice",
            self.cid,
            self.question_id,
            self.answer_id,
            "为什么要分享问题？",
            "这是一个示例回答",
            "我想听听不同人的看法",
        )

    def test_private_and_pending_are_not_public(self):
        with self.assertRaises(NotFound):
            self.store.messages("bob", self.cid)
        with self.assertRaises(NotFound):
            self.store.append_message("bob", self.cid, "user", "偷看")
        pid = self.submit()
        self.assertEqual(self.store.feed(), [])
        with self.assertRaises(NotFound):
            self.store.public_post(pid)
        with self.assertRaises(NotFound):
            self.store.fork("bob", pid)

    def test_public_snapshot_and_private_fork(self):
        pid = self.submit()
        self.store.review_post(pid, approve=True)
        post = self.store.public_post(pid)
        self.assertEqual(post.question, "为什么要分享问题？")
        self.assertEqual(post.answer, "这是一个示例回答")
        self.assertFalse(hasattr(post, "conversation_id"))
        fork_id = self.store.fork("bob", pid)
        fork_text = " ".join(m.text for m in self.store.messages("bob", fork_id))
        self.assertNotIn("13800000000", fork_text)
        self.assertEqual(len(self.store.messages("bob", fork_id)), 2)
        with self.assertRaises(NotFound):
            self.store.messages("alice", fork_id)

    def test_excerpt_must_belong_to_the_owner_and_original_message(self):
        with self.assertRaises(NotFound):
            self.store.submit_post(
                "bob", self.cid, self.question_id, self.answer_id, "为什么", "示例", ""
            )
        with self.assertRaises(InvalidAction):
            self.store.submit_post(
                "alice", self.cid, self.question_id, self.answer_id,
                "另一段凭空添加的问题", "示例", ""
            )
        other_cid = self.store.create_conversation("alice")
        other_qid, _ = self.store.ask("alice", other_cid, "其他问题")
        with self.assertRaises(InvalidAction):
            self.store.submit_post(
                "alice", self.cid, other_qid, self.answer_id, "其他问题", "示例", ""
            )

    def test_withdrawal_removes_public_access(self):
        pid = self.submit()
        self.store.review_post(pid, approve=True)
        with self.assertRaises(NotFound):
            self.store.withdraw_post("bob", pid)
        self.store.withdraw_post("alice", pid)
        with self.assertRaises(NotFound):
            self.store.public_post(pid)
        self.assertEqual(self.store.feed(), [])
        with self.assertRaises(InvalidAction):
            self.store.review_post(pid, approve=True)


if __name__ == "__main__":
    unittest.main()
