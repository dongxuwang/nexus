package ch.epfl.bluebrain.nexus.delta.sdk.identities.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CallerSpec extends AnyWordSpecLike with Matchers {

  "A Caller" should {
    "append the subject to the identities set" in {
      val caller = Caller.unsafe(Identity.Anonymous, Set.empty)
      caller.subject shouldEqual Identity.Anonymous
      caller.identities shouldEqual Set(Identity.Anonymous)
    }
  }

}
