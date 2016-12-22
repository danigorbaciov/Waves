package scorex.transaction

import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalatest.Matchers._
import org.scalatest.enablers.Containing
import org.scalatest.matchers.{BeMatcher, MatchResult}
import scorex.account.PrivateKeyAccount
import scorex.crypto.EllipticCurveImpl
import scorex.transaction.assets.exchange._
import scorex.transaction.assets.{DeleteTransaction, IssueTransaction, ReissueTransaction, TransferTransaction}
import scorex.utils.{ByteArray, NTP}

trait TransactionGen {

  val bytes32gen: Gen[Array[Byte]] = Gen.listOfN(32, Arbitrary.arbitrary[Byte]).map(_.toArray)
  val bytes64gen: Gen[Array[Byte]] = Gen.listOfN(64, Arbitrary.arbitrary[Byte]).map(_.toArray)

  def genBoundedBytes(minSize: Int, maxSize: Int): Gen[Array[Byte]] = {
    Gen.choose(minSize, maxSize) flatMap { sz => Gen.listOfN(sz, Arbitrary.arbitrary[Byte]).map(_.toArray) }
  }

  val accountGen: Gen[PrivateKeyAccount] = bytes32gen.map(seed => new PrivateKeyAccount(seed))
  val positiveLongGen: Gen[Long] = Gen.choose(1, Long.MaxValue / 3)
  val smallFeeGen: Gen[Long] = Gen.choose(1, 100000000)

  val maxTimeGen: Gen[Long] = Gen.choose(10000L, Order.MaxLiveTime).map(_ + NTP.correctedTime())
  val timestampGen: Gen[Long] = Gen.choose(1, 1000).map(NTP.correctedTime() - _)

  val maxWavesAnountGen: Gen[Long] = Gen.choose(1, 100000000L * 100000000L)

  val wavesAssetGen: Gen[Option[Array[Byte]]] = Gen.const(None)
  val assetIdGen: Gen[Option[Array[Byte]]] = Gen.frequency((1, wavesAssetGen), (10, Gen.option(bytes32gen)))

  val assetPairGen = Gen.zip(assetIdGen, assetIdGen).
    suchThat(p => !ByteArray.sameOption(p._1, p._2)).
    map(p => AssetPair(p._1, p._2))

  val paymentGenerator: Gen[PaymentTransaction] = for {
    amount: Long <- Gen.choose(0, Long.MaxValue)
    fee: Long <- positiveLongGen
    timestamp: Long <- positiveLongGen
    sender: PrivateKeyAccount <- accountGen
    recipient: PrivateKeyAccount <- accountGen
  } yield PaymentTransaction(sender, recipient, amount, fee, timestamp)

  val selfPaymentGenerator: Gen[PaymentTransaction] = for {
    account: PrivateKeyAccount <- accountGen
    amount: Long <- Gen.choose(0, Long.MaxValue)
    fee: Long <- smallFeeGen
    timestamp: Long <- positiveLongGen
  } yield PaymentTransaction(account, account, amount, fee, timestamp)

  val transferGenerator: Gen[TransferTransaction] = for {
    amount: Long <- Gen.choose(0, Long.MaxValue)
    feeAmount: Long <- Gen.choose(0, Long.MaxValue- amount - 1)
    assetId: Option[Array[Byte]] <- Gen.option(bytes32gen)
    feeAssetId: Option[Array[Byte]] <- Gen.option(bytes32gen)
    timestamp: Long <- positiveLongGen
    sender: PrivateKeyAccount <- accountGen
    attachment: Array[Byte] <- genBoundedBytes(0, TransferTransaction.MaxAttachmentSize)
    recipient: PrivateKeyAccount <- accountGen
  } yield TransferTransaction.create(assetId, sender, recipient, amount, timestamp, feeAssetId, feeAmount, attachment).right.get


  val transferGeneratorWithNoneFeeAssetId: Gen[TransferTransaction] = for {
    amount: Long <- Gen.choose(0, Long.MaxValue)
    feeAmount: Long <- Gen.choose(0, Long.MaxValue- amount - 1)
    assetId: Option[Array[Byte]] <- Gen.option(bytes32gen)
    timestamp: Long <- positiveLongGen
    sender: PrivateKeyAccount <- accountGen
    attachment: Array[Byte] <- genBoundedBytes(0, TransferTransaction.MaxAttachmentSize)
    recipient: PrivateKeyAccount <- accountGen
  } yield TransferTransaction.create(assetId, sender, recipient, amount, timestamp, None, feeAmount, attachment).right.get


  val selfTransferGenerator: Gen[TransferTransaction] = for {
    amount: Long <- Gen.choose(0, Long.MaxValue)
    feeAmount: Long <- smallFeeGen
    assetId: Option[Array[Byte]] <- Gen.option(bytes32gen)
    feeAssetId: Option[Array[Byte]] <- Gen.option(bytes32gen)
    timestamp: Long <- positiveLongGen
    account: PrivateKeyAccount <- accountGen
    attachment: Array[Byte] <- genBoundedBytes(0, TransferTransaction.MaxAttachmentSize)
  } yield TransferTransaction.create(assetId, account, account, amount, timestamp, feeAssetId, feeAmount, attachment).right.get

  val selfTransferWithWavesFeeGenerator: Gen[TransferTransaction] = for {
    amount: Long <- Gen.choose(0, Long.MaxValue)
    feeAmount: Long <- smallFeeGen
    assetId: Option[Array[Byte]] <- Gen.option(bytes32gen)
    timestamp: Long <- positiveLongGen
    account: PrivateKeyAccount <- accountGen
    attachment: Array[Byte] <- genBoundedBytes(0, TransferTransaction.MaxAttachmentSize)
  } yield TransferTransaction.create(assetId, account, account, amount, timestamp, None, feeAmount, attachment).right.get

  val orderGenerator: Gen[(Order, PrivateKeyAccount)] = for {
    sender: PrivateKeyAccount <- accountGen
    matcher: PrivateKeyAccount <- accountGen
    pair: AssetPair <- assetPairGen
    price: Long <- maxWavesAnountGen
    amount: Long <- maxWavesAnountGen
    maxtTime: Long <- maxTimeGen
    matcherFee: Long <- maxWavesAnountGen
  } yield (Order(sender, matcher, pair.first, pair.second, price, amount, maxtTime, matcherFee), sender)

  val issueReissueGenerator: Gen[(IssueTransaction, IssueTransaction, ReissueTransaction, DeleteTransaction)] = for {
    sender: PrivateKeyAccount <- accountGen
    assetName <- genBoundedBytes(IssueTransaction.MinAssetNameLength, IssueTransaction.MaxAssetNameLength)
    description <- genBoundedBytes(0, IssueTransaction.MaxDescriptionLength)
    quantity <- positiveLongGen
    deleteAmount <- Gen.choose(0L, quantity)
    decimals <- Gen.choose(0: Byte, 8: Byte)
    reissuable <- Arbitrary.arbitrary[Boolean]
    reissuable2 <- Arbitrary.arbitrary[Boolean]
    fee <- Gen.choose(1L, 2000000L)
    iFee <- Gen.choose(IssueTransaction.MinFee, 2 * IssueTransaction.MinFee)
    timestamp <- positiveLongGen
  } yield {
    val issue = IssueTransaction.create(sender, assetName, description, quantity, decimals, reissuable, iFee, timestamp).right.get
    val issue2 = IssueTransaction.create(sender, assetName, description, quantity, decimals,
      reissuable, iFee, Math.max(timestamp, 1476459220001L)).right.get
    val reissue = ReissueTransaction.create(sender, issue.assetId, quantity, reissuable2, fee, timestamp).right.get
    val delete = DeleteTransaction.create(sender, issue.assetId, deleteAmount, fee, timestamp).right.get
    (issue, issue2, reissue, delete)
  }


  val issueGenerator: Gen[IssueTransaction] = issueReissueGenerator.map(_._1)
  val reissueGenerator: Gen[ReissueTransaction] = issueReissueGenerator.map(_._3)
  val deleteGenerator: Gen[DeleteTransaction] = issueReissueGenerator.map(_._4)

  val invalidOrderGenerator: Gen[Order] = for {
    sender: PrivateKeyAccount <- accountGen
    matcher: PrivateKeyAccount <- accountGen
    pair <- assetPairGen
    price: Long <- Arbitrary.arbitrary[Long]
    amount: Long <- Arbitrary.arbitrary[Long]
    maxtTime: Long <- Arbitrary.arbitrary[Long]
    matcherFee: Long <- Arbitrary.arbitrary[Long]
  } yield Order(sender, matcher, pair.first, pair.second, price, amount, maxtTime, matcherFee)

  val orderMatchGenerator: Gen[(OrderMatch, PrivateKeyAccount)] = for {
    sender1: PrivateKeyAccount <- accountGen
    sender2: PrivateKeyAccount <- accountGen
    matcher: PrivateKeyAccount <- accountGen
    assetPair <- assetPairGen
    spendAssetID: Array[Byte] <- bytes32gen
    receiveAssetID: Array[Byte] <- bytes32gen
    price: Long <- maxWavesAnountGen
    amount1: Long <- maxWavesAnountGen
    amount2: Long <- maxWavesAnountGen
    matchedAmount: Long <- Gen.choose(Math.min(amount1, amount2)/2, Math.min(amount1, amount2))
    maxtTime: Long <- maxTimeGen
    matcherFee: Long <- maxWavesAnountGen
  } yield {
    val o1 = Order.buy(sender1, matcher, assetPair, price, amount1, maxtTime, matcherFee)
    val o2 = Order.sell(sender2, matcher, assetPair, price, amount2, maxtTime, matcherFee)
    val buyFee = (BigInt(matcherFee) * BigInt(matchedAmount) / BigInt(amount1)).longValue()
    val sellFee = (BigInt(matcherFee) * BigInt(matchedAmount) / BigInt(amount2)).longValue()
    val unsigned = OrderMatch(o1, o2, price, matchedAmount,
      buyFee, sellFee, (buyFee + sellFee) / 2, maxtTime - 100, Array())
    val sig = EllipticCurveImpl.sign(matcher, unsigned.toSign)
    (unsigned.copy(signature = sig), matcher)
  }

  val orderCancelGenerator: Gen[(OrderCancelTransaction, PrivateKeyAccount)] = for {
    sender: PrivateKeyAccount <- accountGen
    orderId <- bytes32gen
    assetPair <- assetPairGen
    timestamp: Long <- timestampGen
    fee: Long <- maxWavesAnountGen
  } yield (OrderCancelTransaction(sender, assetPair.first, assetPair.second, orderId, fee, timestamp), sender)

  implicit val orderMatchArb: Arbitrary[(OrderMatch, PrivateKeyAccount)] = Arbitrary { orderMatchGenerator }
  implicit val orderArb: Arbitrary[(Order, PrivateKeyAccount)] = Arbitrary { orderGenerator }
  implicit val privateKeyAccArb: Arbitrary[PrivateKeyAccount] = Arbitrary { accountGen }

  def validOrderMatch = orderMatchGenerator.sample.get

  /**
    * Implicit to support <code>Containing</code> nature of <code>Validation</code>.
    */
  implicit val containingNatureOfValidation: Containing[Validation] =
    new Containing[Validation] {
      def contains(v: Validation, ele: Any): Boolean =
        !v.status && v.labels.contains(ele.toString)
      def containsOneOf(v: Validation, elements: scala.collection.Seq[Any]): Boolean = {
        !v.status && elements.map(_.toString).map(v.labels.contains).reduce(_ || _)
      }
      def containsNoneOf(v: Validation, elements: scala.collection.Seq[Any]): Boolean = {
        v.status || elements.map(_.toString).map(v.labels.contains).reduce(!_ && !_)
      }
  }

  class ValidationMatcher extends BeMatcher[Validation] {
    def apply(left: Validation): MatchResult =
      MatchResult(
        left.status,
        left.toString + " was invalid",
        left.toString + " was valid"
      )
  }
  val valid = new ValidationMatcher
  val invalid = not (valid)

  def copyWithFeeAssetId(transferTransaction: TransferTransaction, feeAssetId: Option[Array[Byte]]) : TransferTransaction ={
   TransferTransaction.create(transferTransaction.assetId,
     transferTransaction.sender,
     transferTransaction.recipient,
     transferTransaction.amount,
     transferTransaction.timestamp,feeAssetId,transferTransaction.fee,transferTransaction.attachment,transferTransaction.signature).right.get
  }
}
