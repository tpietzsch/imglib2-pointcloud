package net.imglib2.kdtree;

import ij.ImageJ;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.imglib2.Interval;
import net.imglib2.KDTree;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.neighborsearch.NearestNeighborSearchInterpolatorFactory;
import net.imglib2.kdtree.KDTreeData.PositionsLayout;
import net.imglib2.neighborsearch.NearestNeighborSearch;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.DoubleArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import static net.imglib2.kdtree.KDTreeData.PositionsLayout.FLAT;
import static net.imglib2.kdtree.KDTreeData.PositionsLayout.NESTED;

public class IoPlayground
{
	public static void main( String[] args ) throws IOException
	{
//		KDTree< ARGBType > kdtree = createKDTree();
//		writeToN5( kdtree );


		KDTree< ARGBType > kdtree = readFromN5();
		Interval interval = Intervals.createMinSize( 0, 0, 320, 200 );
		show( kdtree, interval );
	}

	private static void show( final KDTree< ARGBType> kdtree, final Interval interval )
	{
		new ImageJ();

		NearestNeighborSearch< ARGBType > search = new NearestNeighborSearchOnKDTree<>( kdtree );
		RealRandomAccessible< ARGBType > interpolated = Views.interpolate( search, new NearestNeighborSearchInterpolatorFactory<>() );
		RandomAccessibleInterval< ARGBType > view = Views.interval( Views.raster( interpolated ), interval );
		ImageJFunctions.show( view );
	}

	static final String VALUES = "values";

	private static void writeToN5( final KDTree< ARGBType> kdtree ) throws IOException
	{
		final String basePath = "/Users/pietzsch/Desktop/kdtree.n5";
		final N5Writer n5 = new N5FSWriter( basePath );

		final ValueWriter< ARGBType > valueWriter = ( values, n5Writer, pointcloudPath ) -> {
			final RandomAccessibleInterval< UnsignedIntType > source = Converters.convert(
					values,
					( argb, uint ) -> uint.setInt( argb.get() ),
					new UnsignedIntType() );
			final int[] blockSize = { safeInt( values.dimension( 0 ) ) };
			final String valuesPath = n5Writer.groupPath( pointcloudPath, VALUES );
			N5Utils.save( source, n5Writer, valuesPath, blockSize, new RawCompression() );
		};

		writeToN5( kdtree, valueWriter, n5, "my-tree" );
	}

	private static KDTree< ARGBType > readFromN5() throws IOException
	{
		final String basePath = "/Users/pietzsch/Desktop/kdtree.n5";
		final N5Reader n5 = new N5FSReader( basePath );

		final ValueReader< ARGBType > valueReader = ( n5Reader, pointcloudPath ) -> {
			final String valuesPath = n5Reader.groupPath( pointcloudPath, VALUES );
			final RandomAccessibleInterval< UnsignedIntType > uintValues = N5Utils.open( n5Reader, valuesPath );
			final RandomAccessibleInterval< ARGBType > values = Converters.convert( uintValues, ( uint, argb ) -> argb.set( uint.getInt() ), new ARGBType() );
			return values;
		};

		return readFromN5( valueReader, n5, "my-tree" );
	}




















	static final String COORDINATES = "coordinates";

	interface ValueWriter< T >
	{
		void write( RandomAccessibleInterval< T > values, final N5Writer n5, final String pointcloudPath ) throws IOException;
	}

	interface ValueReader< T >
	{
		RandomAccessibleInterval< T > read( final N5Reader n5, final String pointcloudPath ) throws IOException;
	}

	private static < T > void writeToN5(
			final KDTree< T > kdtree,
			final ValueWriter< T > valueWriter,
			final N5Writer n5,
			final String path
	) throws IOException
	{
		final KDTreeData< T > data = kdtree.treeData();
		final int numDimensions = data.numDimensions();
		final int numPoints = data.size();
		final PositionsLayout layout = data.layout();

		final Map< String, Object > attributes = new HashMap<>();
		attributes.put( "pointcloud", "1.0.0" );
		attributes.put( "type", "kdtree" );
		attributes.put( "kdtree-version", "0.1" );
		attributes.put( "flatten-coordinates", layout == FLAT );

		n5.createGroup( path );
		n5.setAttributes( path, attributes );

		final long[] dimensions = { numDimensions, numPoints };
		final int[] blockSize = ( layout == FLAT )
				? new int[] { numDimensions, numPoints }
				: new int[] { 1, numPoints };

		final DatasetAttributes datasetAttributes = new DatasetAttributes( dimensions, blockSize, DataType.FLOAT64, new RawCompression() );
		final String positionsPath = n5.groupPath( path, COORDINATES );
		n5.createDataset( positionsPath, datasetAttributes );

		final long[] gridPosition = new long[ 2 ];
		if ( layout == FLAT )
		{
			double[] bdata = data.flatPositions();
			final DoubleArrayDataBlock block = new DoubleArrayDataBlock( blockSize, gridPosition, bdata );
			n5.writeBlock( positionsPath, datasetAttributes, block );
		}
		else
		{
			double[][] bdata = data.positions();
			for ( int d = 0; d < numDimensions; d++ )
			{
				gridPosition[ 0 ] = d;
				final DoubleArrayDataBlock block = new DoubleArrayDataBlock( blockSize, gridPosition, bdata[ d ] );
				n5.writeBlock( positionsPath, datasetAttributes, block );
			}
		}

		valueWriter.write( data.values(), n5, path );
	}

	private static < T > KDTree< T > readFromN5(
			final ValueReader< T > valueReader,
			final N5Reader n5,
			final String path
	) throws IOException
	{
		final PositionsLayout layout =
				n5.getAttribute( path, "flatten-coordinates", Boolean.class )
						? FLAT
						: NESTED;
		final String positionsPath = n5.groupPath( path, COORDINATES );
		final DatasetAttributes datasetAttributes = n5.getDatasetAttributes( positionsPath );
		final int numDimensions = safeInt( datasetAttributes.getDimensions()[ 0 ] );
//		final int numPoints = safeInt( datasetAttributes.getDimensions()[ 1 ] );

		final RandomAccessibleInterval< T > values = valueReader.read( n5, path );
		if ( layout == FLAT )
		{
			final double[] flatPositions = ( double[] ) n5.readBlock( positionsPath, datasetAttributes, 0, 0 ).getData();
			return new KDTree<>( new KDTreeData<>( flatPositions, values ) );
		}
		else
		{
			final double[][] positions = new double[ numDimensions ][];
			for ( int d = 0; d < numDimensions; d++ )
			{
				positions[ d ] = ( double[] ) n5.readBlock( positionsPath, datasetAttributes, d, 0 ).getData();
			}
			return new KDTree<>( new KDTreeData<>( positions, values ) );
		}
	}

	private static int safeInt( final long value )
	{
		if ( value > Integer.MAX_VALUE )
			throw new IllegalArgumentException( "value too large" );
		return ( int ) value;
	}


















	static KDTree<ARGBType> createKDTree()
	{
		List< ARGBType > colors = Arrays.asList( colorsArray );
		List< RealPoint > coordinates = Arrays.asList( coordinatesArray );
		return new KDTree<>( colors, coordinates );
	}

	static final ARGBType[] colorsArray = new ARGBType[] {
		new ARGBType(4464137),new ARGBType(2295808),new ARGBType(3211520),new ARGBType(1115136),new ARGBType(10320735),new ARGBType(460292),new ARGBType(13133844),new ARGBType(1050117),new ARGBType(16172175),new ARGBType(1115652),new ARGBType(983040),new ARGBType(5513989),new ARGBType(722435),new ARGBType(6427396),new ARGBType(1049859),new ARGBType(3740673),new ARGBType(14269596),new ARGBType(9050896),new ARGBType(13201436),new ARGBType(1836806),new ARGBType(10836489),new ARGBType(15586224),new ARGBType(4926228),new ARGBType(8338699),new ARGBType(2757639),new ARGBType(7091991),new ARGBType(9311750),new ARGBType(7813643),new ARGBType(1443334),new ARGBType(3345668),new ARGBType(5649433),new ARGBType(722435),new ARGBType(4529411),new ARGBType(6114386),new ARGBType(9721369),new ARGBType(14794909),new ARGBType(10950672),new ARGBType(16505531),new ARGBType(525313),new ARGBType(10173472),new ARGBType(9855528),new ARGBType(2494217),new ARGBType(394499),new ARGBType(16579034),new ARGBType(262659),new ARGBType(10425870),new ARGBType(12092509),new ARGBType(16441541),new ARGBType(5847590),new ARGBType(13530906),
		new ARGBType(13144400),new ARGBType(1443847),new ARGBType(6956561),new ARGBType(8541988),new ARGBType(4793361),new ARGBType(1509379),new ARGBType(5181189),new ARGBType(11227438),new ARGBType(9126146),new ARGBType(3017990),new ARGBType(11162633),new ARGBType(6702891),new ARGBType(13283730),new ARGBType(15254949),new ARGBType(1312261),new ARGBType(13598755),new ARGBType(9981960),new ARGBType(10703887),new ARGBType(14401968),new ARGBType(13003283),new ARGBType(1575685),new ARGBType(5911581),new ARGBType(10901007),new ARGBType(12413452),new ARGBType(15717294),new ARGBType(14861983),new ARGBType(3477504),new ARGBType(10967098),new ARGBType(14061676),new ARGBType(1115652),new ARGBType(9853476),new ARGBType(12328977),new ARGBType(2888714),new ARGBType(1378052),new ARGBType(7351552),new ARGBType(14197350),new ARGBType(10515022),new ARGBType(984324),new ARGBType(5519394),new ARGBType(10688012),new ARGBType(11099960),new ARGBType(7875584),new ARGBType(1379082),new ARGBType(12282896),new ARGBType(15123104),new ARGBType(722435),new ARGBType(10829608),new ARGBType(8082485),new ARGBType(9196829),new ARGBType(3017731),
		new ARGBType(6633495),new ARGBType(722435),new ARGBType(3809297),new ARGBType(7481359),new ARGBType(2560262),new ARGBType(14389099),new ARGBType(3745314),new ARGBType(3810077),new ARGBType(5714453),new ARGBType(6369298),new ARGBType(15784365),new ARGBType(11168047),new ARGBType(6502162),new ARGBType(6768691),new ARGBType(12947296),new ARGBType(1115399),new ARGBType(12807197),new ARGBType(132613),new ARGBType(15177323),new ARGBType(984324),new ARGBType(1902595),new ARGBType(787974),new ARGBType(5251846),new ARGBType(8273173),new ARGBType(10577444),new ARGBType(14126642),new ARGBType(2361865),new ARGBType(722435),new ARGBType(7424303),new ARGBType(12557690),new ARGBType(11042154),new ARGBType(3737863),new ARGBType(11804690),new ARGBType(1049859),new ARGBType(11560475),new ARGBType(1181192),new ARGBType(16771793),new ARGBType(7359538),new ARGBType(1836802),new ARGBType(3350815),new ARGBType(1574404),new ARGBType(5188371),new ARGBType(1115910),new ARGBType(9377032),new ARGBType(8206853),new ARGBType(4925975),new ARGBType(2231045),new ARGBType(590849),new ARGBType(11494167),new ARGBType(16645854),
		new ARGBType(6695168),new ARGBType(15521455),new ARGBType(12674633),new ARGBType(12094829),new ARGBType(7290389),new ARGBType(7868421),new ARGBType(918784),new ARGBType(10245944),new ARGBType(11230746),new ARGBType(16777188),new ARGBType(11557908),new ARGBType(1574145),new ARGBType(13929013),new ARGBType(12936975),new ARGBType(1180675),new ARGBType(7880458),new ARGBType(722435),new ARGBType(1705472),new ARGBType(10507537),new ARGBType(11212553),new ARGBType(3410693),new ARGBType(1050883),new ARGBType(13267219),new ARGBType(10365207),new ARGBType(1640449),new ARGBType(7552270),new ARGBType(786954),new ARGBType(10965779),new ARGBType(1835776),new ARGBType(10308369),new ARGBType(7162925),new ARGBType(2360836),new ARGBType(10259329),new ARGBType(3019021),new ARGBType(1443847),new ARGBType(7487779),new ARGBType(3609351),new ARGBType(1900544),new ARGBType(722181),new ARGBType(13331472),new ARGBType(2360578),new ARGBType(9519623),new ARGBType(1049344),new ARGBType(1310720),new ARGBType(786945),new ARGBType(918531),new ARGBType(13466657),new ARGBType(13346441),new ARGBType(984324),new ARGBType(10246947),
		new ARGBType(5254164),new ARGBType(5907982),new ARGBType(9917222),new ARGBType(2820864),new ARGBType(11959899),new ARGBType(14978655),new ARGBType(12091710),new ARGBType(15457469),new ARGBType(2427141),new ARGBType(12610632),new ARGBType(14851192),new ARGBType(12874532),new ARGBType(1378054),new ARGBType(16773317),new ARGBType(1312000),new ARGBType(6701598),new ARGBType(14661783),new ARGBType(13341528),new ARGBType(8796678),new ARGBType(984326),new ARGBType(9195047),new ARGBType(16769730),new ARGBType(8535816),new ARGBType(984324),new ARGBType(7285760),new ARGBType(918530),new ARGBType(722435),new ARGBType(460290),new ARGBType(3937542),new ARGBType(13793565),new ARGBType(6306593),new ARGBType(2821900),new ARGBType(2888965),new ARGBType(15444094),new ARGBType(10049306),new ARGBType(13333263),new ARGBType(13529888),new ARGBType(919044),new ARGBType(5647892),new ARGBType(16176815),new ARGBType(590597),new ARGBType(2492928),new ARGBType(10243079),new ARGBType(13925664),new ARGBType(10753805),new ARGBType(6963996),new ARGBType(1508352),new ARGBType(2885891),new ARGBType(2953739),new ARGBType(10111744),
		new ARGBType(3148034),new ARGBType(9123842),new ARGBType(13472083),new ARGBType(11760962),new ARGBType(7483655),new ARGBType(13335115),new ARGBType(11099684),new ARGBType(8723728),new ARGBType(3803656),new ARGBType(9715979),new ARGBType(1377280),new ARGBType(9321221),new ARGBType(1509128),new ARGBType(525580),new ARGBType(10835724),new ARGBType(3875607),new ARGBType(4265735),new ARGBType(984324),new ARGBType(15055512),new ARGBType(13588294),new ARGBType(1049865),new ARGBType(5319446),new ARGBType(4330752),new ARGBType(3017216),new ARGBType(15918030),new ARGBType(7354900),new ARGBType(5969664),new ARGBType(4398352),new ARGBType(11228428),new ARGBType(14849903),new ARGBType(5386779),new ARGBType(1180675),new ARGBType(6951692),new ARGBType(1115397),new ARGBType(4333840),new ARGBType(7750938),new ARGBType(11493395),new ARGBType(8537867),new ARGBType(12678673),new ARGBType(8266762),new ARGBType(10777416),new ARGBType(13335320),new ARGBType(1640965),new ARGBType(10566434),new ARGBType(15783085),new ARGBType(3608577),new ARGBType(525317),new ARGBType(1050119),new ARGBType(1312261),new ARGBType(4202515),
		new ARGBType(1312261),new ARGBType(458760),new ARGBType(984324),new ARGBType(14865340),new ARGBType(9595211),new ARGBType(11558418),new ARGBType(12877402),new ARGBType(983811),new ARGBType(1771009),new ARGBType(5319957),new ARGBType(1640195),new ARGBType(6370836),new ARGBType(4005138),new ARGBType(12549189),new ARGBType(13728073),new ARGBType(4402212),new ARGBType(10044417),new ARGBType(6438438),new ARGBType(1115912),new ARGBType(8143630),new ARGBType(4003333),new ARGBType(10122838),new ARGBType(11033104),new ARGBType(11098170),new ARGBType(786432),new ARGBType(7618864),new ARGBType(6237450),new ARGBType(8076043),new ARGBType(13334355),new ARGBType(13402458),new ARGBType(12558725),new ARGBType(3346179),new ARGBType(984326),new ARGBType(16113343),new ARGBType(2365464),new ARGBType(1705729),new ARGBType(722181),new ARGBType(7152911),new ARGBType(12478485),new ARGBType(13402458),new ARGBType(589825),new ARGBType(918533),new ARGBType(7944478),new ARGBType(590849),new ARGBType(10111240),new ARGBType(13923617),new ARGBType(16509646),new ARGBType(4135945),new ARGBType(10440715),new ARGBType(9202520),
		new ARGBType(984324),new ARGBType(591361),new ARGBType(15246455),new ARGBType(11489843),new ARGBType(2100486),new ARGBType(3743253),new ARGBType(1639938),new ARGBType(14598813),new ARGBType(7611922),new ARGBType(655618),new ARGBType(2558470),new ARGBType(9981707),new ARGBType(5442051),new ARGBType(9387526),new ARGBType(11096832),new ARGBType(1048832),new ARGBType(16307629),new ARGBType(3677713),new ARGBType(3417124),new ARGBType(1574404),new ARGBType(10243851),new ARGBType(3080960),new ARGBType(590597),new ARGBType(2363136),new ARGBType(1312261),new ARGBType(2360832),new ARGBType(13797473),new ARGBType(15580028),new ARGBType(14387045),new ARGBType(9180944),new ARGBType(10510360),new ARGBType(12664112),new ARGBType(8672551),new ARGBType(9125120),new ARGBType(7027217),new ARGBType(6430478),new ARGBType(6040847),new ARGBType(3737856),new ARGBType(918790),new ARGBType(7355929),new ARGBType(590849),new ARGBType(4858897),new ARGBType(13861139),new ARGBType(12676167),new ARGBType(5911832),new ARGBType(13398055),new ARGBType(6819852),new ARGBType(4992797),new ARGBType(5249801),new ARGBType(10180374),
		new ARGBType(5384974),new ARGBType(16774610),new ARGBType(1509632),new ARGBType(1180676),new ARGBType(13400661),new ARGBType(13399834),new ARGBType(13803118),new ARGBType(1246983),new ARGBType(13465878),new ARGBType(1771781),new ARGBType(1771781),new ARGBType(394497),new ARGBType(11702892),new ARGBType(7753268),new ARGBType(6631957),new ARGBType(1378054),new ARGBType(1837314),new ARGBType(458760),new ARGBType(2492928),new ARGBType(7817519),new ARGBType(722435),new ARGBType(10096905),new ARGBType(10639383),new ARGBType(1312261),new ARGBType(525313),new ARGBType(7752489),new ARGBType(6501649),new ARGBType(5655878),new ARGBType(2625550),new ARGBType(11167000),new ARGBType(13012571),new ARGBType(13988885),new ARGBType(8917772),new ARGBType(8000266),new ARGBType(15056784),new ARGBType(10242325),new ARGBType(4461568),new ARGBType(3676688),new ARGBType(15847842),new ARGBType(13808534),new ARGBType(1640199),new ARGBType(10578976),new ARGBType(8081456),new ARGBType(8983563),new ARGBType(9981457),new ARGBType(7222287),new ARGBType(10314011),new ARGBType(13275733),new ARGBType(1115910),new ARGBType(13728023),
		new ARGBType(12546081),new ARGBType(14052949),new ARGBType(12151056),new ARGBType(15055514),new ARGBType(13599834),new ARGBType(15585448),new ARGBType(6438436),new ARGBType(14978655),new ARGBType(1312262),new ARGBType(7678225),new ARGBType(5317395),new ARGBType(8734745),new ARGBType(9792074),new ARGBType(3547422),new ARGBType(11826480),new ARGBType(9984802),new ARGBType(6039050),new ARGBType(5845790),new ARGBType(14584935),new ARGBType(15387046),new ARGBType(14248788),new ARGBType(5846301),new ARGBType(14650155),new ARGBType(11359759),new ARGBType(10769690),new ARGBType(590595),new ARGBType(197379),new ARGBType(14913363),new ARGBType(9654548),new ARGBType(11082001),new ARGBType(1312262),new ARGBType(2954768),new ARGBType(11016708),new ARGBType(14197097),new ARGBType(2164486),new ARGBType(14919290),new ARGBType(5451032),new ARGBType(3806466),new ARGBType(9321993),new ARGBType(4335130),new ARGBType(918531),new ARGBType(3608846),new ARGBType(4857094),new ARGBType(16043677),new ARGBType(1246209),new ARGBType(5450767),new ARGBType(787208),new ARGBType(10845793),new ARGBType(1377793),new ARGBType(15256232),
	};

	static final RealPoint[] coordinatesArray = new RealPoint[] {
		new RealPoint(136.1,56.6),new RealPoint(243.3,88.5),new RealPoint(0.6,48.5),new RealPoint(53.8,178.3),new RealPoint(155.7,159.1),new RealPoint(17.6,126.9),new RealPoint(51.5,43.3),new RealPoint(257.5,187.8),new RealPoint(178.5,5.7),new RealPoint(196.7,116.5),new RealPoint(274.0,110.2),new RealPoint(235.9,127.3),new RealPoint(281.3,182.5),new RealPoint(96.6,109.4),new RealPoint(231.1,188.7),new RealPoint(222.9,15.1),new RealPoint(125.6,37.8),new RealPoint(72.5,187.1),new RealPoint(291.7,0.2),new RealPoint(127.2,109.3),new RealPoint(21.4,80.0),new RealPoint(75.6,37.4),new RealPoint(98.2,173.4),new RealPoint(315.4,87.3),new RealPoint(136.4,156.6),new RealPoint(282.7,84.4),new RealPoint(69.2,113.4),new RealPoint(315.2,126.6),new RealPoint(230.1,178.3),new RealPoint(216.3,152.8),new RealPoint(219.1,100.6),new RealPoint(28.7,189.7),new RealPoint(138.5,53.5),new RealPoint(189.3,53.9),new RealPoint(230.6,29.5),new RealPoint(153.6,36.1),new RealPoint(38.5,123.0),new RealPoint(55.7,163.9),new RealPoint(7.6,183.7),new RealPoint(182.9,145.1),new RealPoint(238.9,54.0),new RealPoint(219.0,113.3),new RealPoint(16.5,138.6),new RealPoint(90.2,2.8),new RealPoint(6.0,170.1),new RealPoint(39.3,125.5),new RealPoint(167.7,92.8),new RealPoint(86.0,7.5),new RealPoint(115.1,91.3),new RealPoint(25.4,39.1),
		new RealPoint(212.5,25.1),new RealPoint(242.3,161.0),new RealPoint(182.9,154.4),new RealPoint(241.2,54.3),new RealPoint(247.1,81.4),new RealPoint(274.9,111.0),new RealPoint(96.6,187.8),new RealPoint(197.7,128.3),new RealPoint(312.5,75.6),new RealPoint(164.6,179.1),new RealPoint(263.6,17.3),new RealPoint(155.4,170.0),new RealPoint(74.6,16.8),new RealPoint(148.5,77.2),new RealPoint(268.9,157.8),new RealPoint(16.6,48.3),new RealPoint(283.3,44.8),new RealPoint(289.0,23.7),new RealPoint(73.8,2.3),new RealPoint(39.7,70.9),new RealPoint(137.8,192.3),new RealPoint(222.0,65.7),new RealPoint(311.8,22.8),new RealPoint(32.7,54.1),new RealPoint(72.8,49.5),new RealPoint(89.7,29.9),new RealPoint(234.6,21.3),new RealPoint(142.1,126.8),new RealPoint(102.5,41.0),new RealPoint(230.8,187.5),new RealPoint(230.6,44.7),new RealPoint(54.3,95.2),new RealPoint(210.9,112.4),new RealPoint(152.9,180.0),new RealPoint(290.3,81.4),new RealPoint(202.5,15.8),new RealPoint(99.3,25.8),new RealPoint(261.9,171.2),new RealPoint(226.9,79.2),new RealPoint(43.4,94.7),new RealPoint(154.3,143.8),new RealPoint(16.1,82.3),new RealPoint(201.7,82.4),new RealPoint(27.1,75.0),new RealPoint(152.7,37.5),new RealPoint(285.0,177.1),new RealPoint(186.2,139.6),new RealPoint(249.6,108.9),new RealPoint(274.7,65.1),new RealPoint(230.3,148.4),
		new RealPoint(260.6,76.9),new RealPoint(20.0,193.5),new RealPoint(145.2,173.3),new RealPoint(160.0,73.1),new RealPoint(219.0,81.3),new RealPoint(104.4,67.1),new RealPoint(316.3,172.2),new RealPoint(191.8,87.1),new RealPoint(223.7,100.4),new RealPoint(234.4,126.2),new RealPoint(155.3,13.6),new RealPoint(277.5,58.1),new RealPoint(180.8,98.2),new RealPoint(182.2,187.5),new RealPoint(220.1,47.0),new RealPoint(196.2,188.7),new RealPoint(56.8,66.5),new RealPoint(19.8,135.6),new RealPoint(55.2,79.2),new RealPoint(122.8,144.1),new RealPoint(245.7,141.9),new RealPoint(50.4,178.0),new RealPoint(288.2,98.3),new RealPoint(220.8,125.5),new RealPoint(217.3,26.9),new RealPoint(16.3,69.9),new RealPoint(120.7,195.7),new RealPoint(26.0,190.3),new RealPoint(139.9,152.2),new RealPoint(143.2,106.9),new RealPoint(207.0,57.9),new RealPoint(56.8,144.3),new RealPoint(38.5,99.8),new RealPoint(278.7,161.4),new RealPoint(262.8,38.7),new RealPoint(196.5,189.4),new RealPoint(66.3,6.1),new RealPoint(161.9,59.6),new RealPoint(279.8,106.3),new RealPoint(316.3,173.1),new RealPoint(118.0,165.8),new RealPoint(111.1,62.9),new RealPoint(270.8,166.9),new RealPoint(73.5,110.6),new RealPoint(289.8,79.8),new RealPoint(224.3,72.2),new RealPoint(243.6,148.2),new RealPoint(6.5,126.0),new RealPoint(255.8,27.2),new RealPoint(90.2,2.4),
		new RealPoint(272.6,62.9),new RealPoint(64.1,193.1),new RealPoint(180.2,135.8),new RealPoint(134.3,43.9),new RealPoint(294.7,124.7),new RealPoint(80.4,111.9),new RealPoint(120.8,132.0),new RealPoint(58.6,174.8),new RealPoint(292.9,45.0),new RealPoint(89.4,1.0),new RealPoint(62.8,16.9),new RealPoint(125.1,126.7),new RealPoint(17.9,67.7),new RealPoint(46.4,51.0),new RealPoint(251.5,173.7),new RealPoint(263.7,64.3),new RealPoint(27.5,185.8),new RealPoint(198.3,118.0),new RealPoint(293.5,58.3),new RealPoint(41.9,98.2),new RealPoint(162.1,196.0),new RealPoint(235.8,189.6),new RealPoint(43.7,47.1),new RealPoint(60.6,182.1),new RealPoint(268.4,118.2),new RealPoint(222.4,15.5),new RealPoint(271.3,123.2),new RealPoint(316.2,7.9),new RealPoint(86.2,156.7),new RealPoint(318.9,27.9),new RealPoint(219.1,72.8),new RealPoint(80.8,152.1),new RealPoint(79.1,25.5),new RealPoint(223.3,153.8),new RealPoint(97.7,146.8),new RealPoint(105.0,5.5),new RealPoint(285.6,99.2),new RealPoint(289.2,136.2),new RealPoint(47.4,194.2),new RealPoint(50.3,9.4),new RealPoint(86.0,132.4),new RealPoint(299.2,56.3),new RealPoint(259.1,100.2),new RealPoint(91.4,49.2),new RealPoint(268.9,98.7),new RealPoint(247.6,194.7),new RealPoint(7.4,36.1),new RealPoint(73.2,34.7),new RealPoint(244.2,185.6),new RealPoint(227.2,20.5),
		new RealPoint(129.0,69.1),new RealPoint(217.9,190.4),new RealPoint(239.5,18.6),new RealPoint(103.5,121.2),new RealPoint(157.9,124.5),new RealPoint(117.6,20.8),new RealPoint(211.1,21.6),new RealPoint(60.3,195.6),new RealPoint(234.9,151.2),new RealPoint(166.6,140.1),new RealPoint(41.6,111.1),new RealPoint(308.1,8.9),new RealPoint(119.3,186.2),new RealPoint(95.2,60.3),new RealPoint(254.1,125.1),new RealPoint(240.3,111.6),new RealPoint(154.8,70.3),new RealPoint(195.0,8.9),new RealPoint(315.7,66.8),new RealPoint(272.6,167.9),new RealPoint(188.6,3.6),new RealPoint(57.2,167.7),new RealPoint(301.5,90.1),new RealPoint(113.8,153.0),new RealPoint(178.0,1.3),new RealPoint(253.5,130.3),new RealPoint(280.8,194.9),new RealPoint(50.7,174.9),new RealPoint(237.3,135.2),new RealPoint(27.8,30.4),new RealPoint(164.6,57.1),new RealPoint(111.7,100.6),new RealPoint(191.9,117.7),new RealPoint(95.8,67.1),new RealPoint(278.4,57.6),new RealPoint(25.1,16.7),new RealPoint(60.8,16.5),new RealPoint(272.0,180.7),new RealPoint(136.2,51.9),new RealPoint(97.7,16.2),new RealPoint(98.5,156.0),new RealPoint(223.1,84.2),new RealPoint(304.7,31.5),new RealPoint(30.9,44.0),new RealPoint(63.1,106.8),new RealPoint(244.4,112.9),new RealPoint(208.4,112.5),new RealPoint(104.7,184.5),new RealPoint(9.8,90.0),new RealPoint(311.9,13.2),
		new RealPoint(114.2,123.7),new RealPoint(310.6,32.2),new RealPoint(203.8,22.2),new RealPoint(205.2,10.5),new RealPoint(304.7,108.6),new RealPoint(111.7,16.4),new RealPoint(59.0,26.1),new RealPoint(71.7,186.3),new RealPoint(97.8,123.3),new RealPoint(310.8,39.1),new RealPoint(235.9,118.2),new RealPoint(241.2,14.9),new RealPoint(88.7,149.3),new RealPoint(294.3,187.3),new RealPoint(308.6,15.4),new RealPoint(137.3,159.5),new RealPoint(222.4,191.2),new RealPoint(267.8,170.1),new RealPoint(121.2,53.6),new RealPoint(40.6,120.0),new RealPoint(105.3,87.5),new RealPoint(315.3,154.8),new RealPoint(275.8,81.1),new RealPoint(286.9,101.1),new RealPoint(63.7,198.3),new RealPoint(316.0,149.3),new RealPoint(141.0,7.3),new RealPoint(218.9,194.7),new RealPoint(266.7,10.0),new RealPoint(46.3,106.7),new RealPoint(148.4,162.1),new RealPoint(247.7,170.3),new RealPoint(91.2,99.8),new RealPoint(151.8,188.1),new RealPoint(226.8,71.3),new RealPoint(251.6,34.4),new RealPoint(307.8,22.8),new RealPoint(286.4,77.5),new RealPoint(23.9,74.0),new RealPoint(59.5,172.0),new RealPoint(218.3,51.7),new RealPoint(48.5,66.0),new RealPoint(263.7,106.9),new RealPoint(192.0,138.0),new RealPoint(138.9,28.1),new RealPoint(126.7,113.8),new RealPoint(200.9,182.0),new RealPoint(154.9,186.6),new RealPoint(242.6,171.5),new RealPoint(193.9,106.0),
		new RealPoint(250.7,165.3),new RealPoint(271.1,125.4),new RealPoint(254.1,188.9),new RealPoint(54.3,194.3),new RealPoint(131.0,100.5),new RealPoint(41.2,83.8),new RealPoint(102.6,40.1),new RealPoint(102.9,164.6),new RealPoint(157.8,177.5),new RealPoint(135.3,68.6),new RealPoint(122.3,191.2),new RealPoint(106.5,5.0),new RealPoint(19.4,95.9),new RealPoint(207.1,10.7),new RealPoint(111.3,37.6),new RealPoint(180.0,117.2),new RealPoint(278.2,9.8),new RealPoint(113.6,62.3),new RealPoint(143.2,189.0),new RealPoint(256.1,61.3),new RealPoint(127.6,116.8),new RealPoint(78.9,162.1),new RealPoint(49.5,32.6),new RealPoint(168.1,148.9),new RealPoint(1.7,181.2),new RealPoint(209.0,119.7),new RealPoint(310.0,149.3),new RealPoint(312.4,97.3),new RealPoint(173.6,134.8),new RealPoint(159.8,132.0),new RealPoint(149.2,103.0),new RealPoint(64.9,153.6),new RealPoint(272.3,159.9),new RealPoint(161.8,98.7),new RealPoint(204.3,83.1),new RealPoint(139.3,196.2),new RealPoint(262.3,103.4),new RealPoint(217.4,134.9),new RealPoint(298.8,4.7),new RealPoint(159.8,132.0),new RealPoint(2.0,109.6),new RealPoint(108.1,153.4),new RealPoint(163.5,69.7),new RealPoint(28.4,182.3),new RealPoint(317.6,32.7),new RealPoint(59.8,16.3),new RealPoint(182.1,74.3),new RealPoint(28.7,96.0),new RealPoint(315.0,13.7),new RealPoint(77.3,20.8),
		new RealPoint(270.8,172.7),new RealPoint(110.8,162.2),new RealPoint(88.2,80.4),new RealPoint(188.0,133.6),new RealPoint(190.6,83.8),new RealPoint(157.9,175.9),new RealPoint(196.0,61.5),new RealPoint(85.2,27.5),new RealPoint(199.6,148.1),new RealPoint(244.6,123.4),new RealPoint(207.6,161.7),new RealPoint(251.6,26.5),new RealPoint(79.4,125.9),new RealPoint(294.0,34.9),new RealPoint(289.3,4.4),new RealPoint(22.7,135.7),new RealPoint(70.2,39.8),new RealPoint(217.4,85.1),new RealPoint(318.1,172.2),new RealPoint(252.3,151.0),new RealPoint(310.9,48.5),new RealPoint(103.3,79.1),new RealPoint(14.3,170.8),new RealPoint(139.9,111.3),new RealPoint(246.8,169.5),new RealPoint(104.1,103.2),new RealPoint(162.7,128.5),new RealPoint(187.9,10.5),new RealPoint(184.4,124.4),new RealPoint(65.9,182.8),new RealPoint(280.3,60.1),new RealPoint(50.4,120.4),new RealPoint(232.8,23.1),new RealPoint(302.7,30.4),new RealPoint(294.3,124.9),new RealPoint(211.9,146.4),new RealPoint(249.4,70.6),new RealPoint(213.7,10.7),new RealPoint(156.4,185.6),new RealPoint(244.5,59.5),new RealPoint(15.5,114.2),new RealPoint(130.1,126.0),new RealPoint(9.4,8.7),new RealPoint(190.3,127.8),new RealPoint(228.6,98.4),new RealPoint(60.4,40.1),new RealPoint(97.2,104.1),new RealPoint(208.8,109.4),new RealPoint(189.9,156.9),new RealPoint(242.7,27.9),
		new RealPoint(3.9,67.2),new RealPoint(92.7,2.1),new RealPoint(266.6,115.0),new RealPoint(202.4,198.7),new RealPoint(80.4,93.0),new RealPoint(11.7,24.3),new RealPoint(66.1,16.9),new RealPoint(108.5,166.3),new RealPoint(20.3,38.2),new RealPoint(100.3,133.7),new RealPoint(241.3,159.5),new RealPoint(8.9,198.3),new RealPoint(153.3,7.9),new RealPoint(90.3,41.1),new RealPoint(298.3,155.4),new RealPoint(260.9,145.9),new RealPoint(259.6,128.0),new RealPoint(271.3,125.3),new RealPoint(168.2,184.3),new RealPoint(172.1,71.1),new RealPoint(38.2,196.4),new RealPoint(36.9,126.6),new RealPoint(305.9,75.4),new RealPoint(241.4,168.1),new RealPoint(18.4,155.2),new RealPoint(242.9,106.3),new RealPoint(261.5,76.0),new RealPoint(70.5,13.5),new RealPoint(190.4,189.9),new RealPoint(53.8,49.2),new RealPoint(217.7,42.8),new RealPoint(48.3,17.5),new RealPoint(89.6,102.7),new RealPoint(85.7,110.7),new RealPoint(174.6,16.8),new RealPoint(312.3,34.4),new RealPoint(0.2,47.9),new RealPoint(130.4,175.5),new RealPoint(159.5,12.4),new RealPoint(146.2,94.1),new RealPoint(156.3,189.9),new RealPoint(236.9,29.4),new RealPoint(130.3,90.2),new RealPoint(90.6,105.6),new RealPoint(318.2,76.8),new RealPoint(294.5,107.4),new RealPoint(237.2,38.3),new RealPoint(195.0,9.5),new RealPoint(267.9,164.5),new RealPoint(35.0,40.1),
		new RealPoint(273.8,28.2),new RealPoint(38.7,116.5),new RealPoint(255.0,18.4),new RealPoint(180.9,34.0),new RealPoint(96.0,79.0),new RealPoint(168.7,24.8),new RealPoint(180.2,103.1),new RealPoint(117.7,21.5),new RealPoint(0.7,193.1),new RealPoint(215.7,131.5),new RealPoint(217.3,194.6),new RealPoint(311.9,122.3),new RealPoint(131.4,91.6),new RealPoint(193.4,52.7),new RealPoint(225.9,34.2),new RealPoint(230.9,39.7),new RealPoint(293.3,113.6),new RealPoint(83.2,54.0),new RealPoint(175.1,131.0),new RealPoint(142.8,36.1),new RealPoint(45.1,118.5),new RealPoint(222.9,107.9),new RealPoint(49.2,75.5),new RealPoint(264.2,18.8),new RealPoint(289.6,46.2),new RealPoint(311.7,190.1),new RealPoint(18.9,132.2),new RealPoint(53.0,75.9),new RealPoint(8.4,66.2),new RealPoint(29.2,113.5),new RealPoint(146.9,188.5),new RealPoint(204.9,86.8),new RealPoint(50.9,91.6),new RealPoint(197.6,25.0),new RealPoint(251.8,137.6),new RealPoint(101.8,58.7),new RealPoint(127.1,72.8),new RealPoint(305.9,159.9),new RealPoint(315.9,50.4),new RealPoint(129.5,103.8),new RealPoint(114.1,146.9),new RealPoint(100.1,87.6),new RealPoint(305.8,135.2),new RealPoint(177.7,18.3),new RealPoint(282.0,170.7),new RealPoint(258.5,119.6),new RealPoint(270.3,134.3),new RealPoint(71.0,65.7),new RealPoint(223.8,198.5),new RealPoint(127.0,38.9),
	};
}
