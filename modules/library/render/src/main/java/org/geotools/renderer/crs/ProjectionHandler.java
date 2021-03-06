/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 * 
 *    (C) 2002-2008, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.renderer.crs;

import static org.geotools.referencing.crs.DefaultGeographicCRS.WGS84;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.referencing.operation.transform.ConcatenatedTransform;
import org.geotools.referencing.operation.transform.GeocentricTransform;
import org.opengis.metadata.extent.GeographicBoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.crs.GeographicCRS;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiLineString;
import com.vividsolutions.jts.geom.MultiPoint;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.PrecisionModel;
import com.vividsolutions.jts.geom.prep.PreparedGeometry;
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory;
import com.vividsolutions.jts.precision.EnhancedPrecisionOp;
import com.vividsolutions.jts.precision.GeometryPrecisionReducer;

/**
 * A class that can perform transformations on geometries to handle the singularity of the rendering
 * CRS, deal with geometries that are crossing the dateline, and eventually wrap them around to
 * produce a seamless continuous map effect.<p>
 * 
 * This basic implementation will cut the geometries that get outside of the area of validity of the
 * projection (as provided by the constructor)
 * 
 * WARNING: this API is not finalized and is meant to be used by StreamingRenderer only
 * @author Andrea Aime - OpenGeo
 *
 *
 * @source $URL$
 */
public class ProjectionHandler {

    static final double EPS = 1e-6;

    protected static final Logger LOGGER = org.geotools.util.logging.Logging.getLogger(ProjectionHandler.class);

    ReferencedEnvelope renderingEnvelope;
    
    final ReferencedEnvelope validAreaBounds;
    
    final Geometry validArea;
    
    final PreparedGeometry validaAreaTester;

    final CoordinateReferenceSystem sourceCRS;

    final CoordinateReferenceSystem targetCRS;

    double datelineX = Double.NaN;

    double radius = Double.NaN;

    /**
     * Initializes a projection handler 
     * 
     * @param sourceCRS The source CRS
     * @param validArea The valid area (used to cut geometries that go beyond it)
     * @param renderingEnvelope The target rendering area and target CRS
     * 
     * @throws FactoryException
     */
    public ProjectionHandler(CoordinateReferenceSystem sourceCRS, Envelope validAreaBounds, ReferencedEnvelope renderingEnvelope) throws FactoryException {
        this.renderingEnvelope = renderingEnvelope;
        this.sourceCRS = CRS.getHorizontalCRS(sourceCRS);
        this.targetCRS = renderingEnvelope.getCoordinateReferenceSystem();
        this.validAreaBounds = validAreaBounds != null ? new ReferencedEnvelope(validAreaBounds,
                DefaultGeographicCRS.WGS84) : null;
        this.validArea = null;
        this.validaAreaTester = null;
    }
    
    /**
     * Initializes a projection handler 
     * 
     * @param sourceCRS The source CRS
     * @param validArea The valid area (used to cut geometries that go beyond it)
     * @param renderingEnvelope The target rendering area and target CRS
     * 
     * @throws FactoryException
     */
    public ProjectionHandler(CoordinateReferenceSystem sourceCRS, Geometry validArea, ReferencedEnvelope renderingEnvelope) throws FactoryException {
        if(validArea.isRectangle()) {
            this.renderingEnvelope = renderingEnvelope;
            this.sourceCRS = sourceCRS;
            this.targetCRS = renderingEnvelope.getCoordinateReferenceSystem();
            this.validAreaBounds = new ReferencedEnvelope(validArea.getEnvelopeInternal(),
                    DefaultGeographicCRS.WGS84);
            this.validArea = null;
            this.validaAreaTester = null;
        } else {
            this.renderingEnvelope = renderingEnvelope;
            this.sourceCRS = sourceCRS;
            this.targetCRS = renderingEnvelope.getCoordinateReferenceSystem();
            this.validAreaBounds = new ReferencedEnvelope(validArea.getEnvelopeInternal(),
                    DefaultGeographicCRS.WGS84);
            this.validArea = validArea;
            this.validaAreaTester = PreparedGeometryFactory.prepare(validArea);
        }
    }

    /**
     * Returns the current rendering envelope
     */
    public ReferencedEnvelope getRenderingEnvelope() {
        return renderingEnvelope;
    }
    
    public CoordinateReferenceSystem getSourceCRS() {
        return this.sourceCRS;
    }
    
    /**
     * Returns a set of envelopes that will be used to query the data given the specified rendering
     * envelope and the current query envelope
     */
    public List<ReferencedEnvelope> getQueryEnvelopes()
            throws TransformException, FactoryException {
        CoordinateReferenceSystem renderingCRS = renderingEnvelope.getCoordinateReferenceSystem();
        if(renderingCRS instanceof GeographicCRS && !CRS.equalsIgnoreMetadata(renderingCRS, WGS84)) {
            // special case, if we just transform the coordinates are going to be wrapped by the referencing
            // subsystem directly
            ReferencedEnvelope re = renderingEnvelope;
            List<ReferencedEnvelope> envelopes = new ArrayList<ReferencedEnvelope>();
            envelopes.add(re);

            if(CRS.getAxisOrder(renderingCRS) == CRS.AxisOrder.NORTH_EAST) {
                if (re.getMinY() >= -180.0 && re.getMaxY() <= 180) {
                    return Collections
                            .singletonList(transformEnvelope(renderingEnvelope, sourceCRS));
                }
                
                // We need to split reprojected envelope and normalize it. To be lenient with
                // situations in which the data is just broken (people saying 4326 just because they
                // have no idea at all) we don't actually split, but add elements
                if (re.getMinY() < -180) {
                    envelopes.add(new ReferencedEnvelope(re.getMinY() + 360, 180, re.getMinX(), re
                            .getMaxX(), re.getCoordinateReferenceSystem()));
                }
                if (re.getMaxY() > 180) {
                    envelopes.add(new ReferencedEnvelope(-180, re.getMaxY() - 360, re.getMinX(), re
                            .getMaxX(), re.getCoordinateReferenceSystem()));
                }
                
            } else {
                if (re.getMinX() >= -180.0 && re.getMaxX() <= 180) {
                    return Collections
                            .singletonList(transformEnvelope(renderingEnvelope, sourceCRS));
                }
            
                // We need to split reprojected envelope and normalize it. To be lenient with
                // situations in which the data is just broken (people saying 4326 just because they
                // have no idea at all) we don't actually split, but add elements
                
                if (re.getMinX() < -180) {
                    envelopes.add(new ReferencedEnvelope(re.getMinX() + 360, 180, re.getMinY(), re
                            .getMaxY(), re.getCoordinateReferenceSystem()));
                }
                if (re.getMaxX() > 180) {
                    envelopes.add(new ReferencedEnvelope(-180, re.getMaxX() - 360, re.getMinY(), re
                            .getMaxY(), re.getCoordinateReferenceSystem()));
                }
            }
    
            mergeEnvelopes(envelopes);
            reprojectEnvelopes(sourceCRS, envelopes);
    
            return envelopes;
            
            
        } else {
            if (!Double.isNaN(datelineX) && renderingEnvelope.getMinX() < datelineX
                    && renderingEnvelope.getMaxX() > datelineX
                    && renderingEnvelope.getWidth() < radius) {
                double minX = renderingEnvelope.getMinX();
                double minY = renderingEnvelope.getMinY();
                double maxX = renderingEnvelope.getMaxX();
                double maxY = renderingEnvelope.getMaxY();
                ReferencedEnvelope re1 = new ReferencedEnvelope(minX, datelineX - EPS, minY,
                        maxY, renderingCRS);
                ReferencedEnvelope tx1 = transformEnvelope(re1, WGS84);
                tx1.expandToInclude(180, tx1.getMinY());
                ReferencedEnvelope re2 = new ReferencedEnvelope(datelineX + EPS, maxX, minY,
                        maxY, renderingCRS);
                ReferencedEnvelope tx2 = transformEnvelope(re2, WGS84);
                if (tx2.getMinX() > 180) {
                    tx2.translate(-360, 0);
                }
                tx2.expandToInclude(-180, tx1.getMinY());
                List<ReferencedEnvelope> result = new ArrayList<ReferencedEnvelope>();
                result.add(tx1);
                result.add(tx2);
                mergeEnvelopes(result);
                return result;
            } else {
                return getSourceEnvelopes(renderingEnvelope);
            }
        }
    }

    private List<ReferencedEnvelope> getSourceEnvelopes(ReferencedEnvelope renderingEnvelope)
            throws TransformException, FactoryException {
        // check if we are crossing the dateline
        ReferencedEnvelope re = transformEnvelope(renderingEnvelope, WGS84);
        if (re.getMinX() >= -180.0 && re.getMaxX() <= 180)
            return Collections.singletonList(transformEnvelope(renderingEnvelope, sourceCRS));

        // We need to split reprojected envelope and normalize it. To be lenient with
        // situations in which the data is just broken (people saying 4326 just because they
        // have no idea at all) we don't actually split, but add elements
        List<ReferencedEnvelope> envelopes = new ArrayList<ReferencedEnvelope>();
        envelopes.add(re);
        if (re.getMinX() < -180) {
            envelopes.add(new ReferencedEnvelope(re.getMinX() + 360, 180, re.getMinY(), re
                    .getMaxY(), re.getCoordinateReferenceSystem()));
        }
        if (re.getMaxX() > 180) {
            envelopes.add(new ReferencedEnvelope(-180, re.getMaxX() - 360, re.getMinY(), re
                    .getMaxY(), re.getCoordinateReferenceSystem()));
        }

        mergeEnvelopes(envelopes);
        reprojectEnvelopes(sourceCRS, envelopes);

        return envelopes;
    }

    private ReferencedEnvelope transformEnvelope(ReferencedEnvelope envelope,
            CoordinateReferenceSystem targetCRS) throws TransformException, FactoryException {
        try {
            return envelope.transform(targetCRS, true, 10);
        } catch (Exception e) {
            LOGGER.fine("Failed to reproject the envelope " + envelope + " to " + targetCRS
                    + " trying an area restriction");

            ReferencedEnvelope envWGS84 = envelope.transform(DefaultGeographicCRS.WGS84, true);

            // let's see if we can restrict the area we're reprojecting back using a projection
            // handler for the source CRS
            ProjectionHandler handler = ProjectionHandlerFinder.getHandler(envelope,
                    envelope.getCoordinateReferenceSystem(), false);
            if (handler != null && handler.validAreaBounds != null) {

                Envelope intersection = envWGS84.intersection(validAreaBounds);
                if (intersection.isNull()) {
                    return null;
                } else {
                    try {
                        return ReferencedEnvelope.reference(intersection)
                                .transform(targetCRS, true);
                    } catch (Exception e2) {
                        LOGGER.fine("Failed to reproject the restricted envelope " + intersection
                                + " to " + targetCRS);
                    }

                }
            }

            // ok, let's see if we have an area of validity then
            GeographicBoundingBox bbox = CRS.getGeographicBoundingBox(targetCRS);
            if (bbox != null) {
                ReferencedEnvelope restriction = new ReferencedEnvelope(
                        bbox.getEastBoundLongitude(), bbox.getWestBoundLongitude(),
                        bbox.getSouthBoundLatitude(), bbox.getNorthBoundLatitude(),
                        DefaultGeographicCRS.WGS84);
                Envelope intersection = envWGS84.intersection(restriction);
                if (intersection.isNull()) {
                    return null;
                } else {
                    try {
                        return ReferencedEnvelope.reference(intersection)
                                .transform(targetCRS, true);
                    } catch (Exception e2) {
                        LOGGER.fine("Failed to reproject the restricted envelope " + intersection
                                + " to " + targetCRS);
                    }

                }

            }

            throw new TransformException("All attemptsto reproject the envelope " + envelope
                    + " to " + targetCRS + " failed");
        }
    }

    protected void reprojectEnvelopes(CoordinateReferenceSystem queryCRS,
            List<ReferencedEnvelope> envelopes) throws TransformException, FactoryException {
        // reproject the surviving envelopes
        for (int i = 0; i < envelopes.size(); i++) {
            envelopes.set(i, transformEnvelope(envelopes.get(i), queryCRS));
        }
    }

    private void mergeEnvelopes(List<ReferencedEnvelope> envelopes) {
        // the envelopes generated might overlap, check and merge if necessary, we
        // don't want the data backend to deal with ORs against the spatial index
        // unless necessary
        boolean merged = true;
        while (merged && envelopes.size() > 1) {
            merged = false;
            for (int i = 0; i < envelopes.size() - 1; i++) {
                ReferencedEnvelope curr = envelopes.get(i);
                for (int j = i + 1; j < envelopes.size();) {
                    ReferencedEnvelope next = envelopes.get(j);
                    if (curr.intersects((Envelope) next)) {
                        curr.expandToInclude(next);
                        envelopes.remove(j);
                        merged = true;
                    } else {
                        j++;
                    }
                }
            }
        }
    }

    /**
     * Returns true if the geometry needs special handling
     */
    public boolean requiresProcessing(Geometry geometry) {
        // if there is no valid area, no cutting is required
        if(validAreaBounds == null)
            return false;
        
        // if not reprojection is going on, we don't need to cut
        if (CRS.equalsIgnoreMetadata(sourceCRS, renderingEnvelope.getCoordinateReferenceSystem())) {
            return false;
        }
        
        return true;
    }

    /**
     * Pre processes the geometry, e.g. cuts it, splits it, etc. in its native srs. May return null
     * if the geometry is not to be drawn
     */
    public Geometry preProcess(Geometry geometry) throws TransformException, FactoryException {
        // if there is no valid area, no cutting is required either
        if(validAreaBounds == null)
            return geometry;
        
        // if not reprojection is going on, we don't need to cut
        CoordinateReferenceSystem geometryCRS = CRS.getHorizontalCRS(sourceCRS);
        if (geometryCRS == null
                || CRS.findMathTransform(geometryCRS,
                        renderingEnvelope.getCoordinateReferenceSystem()).isIdentity()) {
            return geometry;
        }
        
        Geometry mask;
        // fast path for the rectangular case, more complex one for the
        // non rectangular one
        if(validArea == null) {
            // if the geometry is within the valid area for this projection
            // just skip expensive cutting
            ReferencedEnvelope ge = new ReferencedEnvelope(geometry.getEnvelopeInternal(), geometryCRS);
            ReferencedEnvelope geWGS84 = ge.transform(WGS84, true);
            if (validAreaBounds.contains((Envelope) geWGS84)) {
                return geometry;
            }

            // we need to cut, first thing, we intersect the geometry envelope
            // and the valid area in WGS84, which is a neutral, everything can
            // be turned into it, and then turn back the intersection into
            // the origin SRS
            ReferencedEnvelope envIntWgs84 = new ReferencedEnvelope(validAreaBounds.intersection(geWGS84), WGS84);
            
            // if the intersection is empty the geometry is completely outside of the valid area, skip it
            if(envIntWgs84.isEmpty()) {
                return null;
            }
                
            ReferencedEnvelope envInt = envIntWgs84.transform(geometryCRS, true);
            mask = JTS.toGeometry((Envelope) envInt);
        } else {
            // if the geometry is within the valid area for this projection
            // just skip expensive cutting
            ReferencedEnvelope ge = new ReferencedEnvelope(geometry.getEnvelopeInternal(), geometryCRS);
            ReferencedEnvelope geWGS84 = ge.transform(WGS84, true);
            if (validaAreaTester.contains(JTS.toGeometry(geWGS84))) {
                return geometry;
            }

            // we need to cut, first thing, we intersect the geometry envelope
            // and the valid area in WGS84, which is a neutral, everything can
            // be turned into it, and then turn back the intersection into
            // the origin SRS
            ReferencedEnvelope envIntWgs84 = new ReferencedEnvelope(validAreaBounds.intersection(geWGS84), WGS84);
            
            // if the intersection is empty the geometry is completely outside of the valid area, skip it
            if(envIntWgs84.isEmpty()) {
                return null;
            } 
            
            Polygon polyIntWgs84 = JTS.toGeometry(envIntWgs84);
            Geometry maskWgs84 = intersect(validArea, polyIntWgs84, geometryCRS);
            if(maskWgs84 == null || maskWgs84.isEmpty()) {
                return null;
            }
            mask = JTS.transform(maskWgs84, CRS.findMathTransform(WGS84, geometryCRS));
        }
        
        return intersect(geometry, mask, geometryCRS);
    }

    private Geometry intersect(Geometry geometry, Geometry mask,
            CoordinateReferenceSystem geometryCRS) {
        // this seems to cause issues to JTS, reduce to
        // single geometry when possible (http://jira.codehaus.org/browse/GEOS-6570)
        if (geometry instanceof GeometryCollection) {
            int numGeometries = geometry.getNumGeometries();
            if (numGeometries == 1) {
                geometry = geometry.getGeometryN(0);
            } else {
                // go piecewise, the JTS intersection can be pretty fragile in these cases
                // and take a lot of time
                List<Geometry> elements = new ArrayList<>();
                String geometryType = numGeometries > 0 ? geometry.getGeometryN(0)
                        .getGeometryType() : null;
                for (int i = 0; i < numGeometries; i++) {
                    Geometry g = geometry.getGeometryN(i);
                    if (g.getEnvelopeInternal().intersects(mask.getEnvelopeInternal())) {
                        Geometry intersected = intersect(g, mask, geometryCRS);
                        if (intersected != null) {
                            if (intersected.getGeometryType().equals(geometryType)) {
                                elements.add(intersected);
                            } else if (intersected instanceof GeometryCollection) {
                                addGeometries(elements, (GeometryCollection) intersected,
                                        geometryType);
                            }
                        }
                    }
                }

                if (elements.size() == 0) {
                    return null;
                }

                if(geometry instanceof MultiPoint) {
                    Point[] array = elements.toArray(new Point[elements.size()]);
                    return geometry.getFactory().createMultiPoint(array);
                } else if (geometry instanceof MultiLineString) {
                    LineString[] array = elements.toArray(new LineString[elements.size()]);
                    return geometry.getFactory().createMultiLineString(array);
                } else if (geometry instanceof MultiPolygon) {
                    Polygon[] array = elements.toArray(new Polygon[elements.size()]);
                    return geometry.getFactory().createMultiPolygon(array);
                } else {
                    Geometry[] array = elements.toArray(new Geometry[elements.size()]);
                    return geometry.getFactory().createGeometryCollection(array);
                }
            }
        }
        Geometry result = null;
        try {
            result = geometry.intersection(mask);
        } catch(Exception e1) {
            // try a precision reduction approach, starting from mm and scaling up to km
            double precision;
            if (CRS.getProjectedCRS(geometryCRS) != null) {
                precision = 1e-3;
            } else {
                precision = 1e-3 / 100000; // 1 degree roughly 100km
            }
            // from mm to km
            for (int i = 0; i < 6; i++) {
                GeometryPrecisionReducer reducer = new GeometryPrecisionReducer(new PrecisionModel(
                        1 / precision));
                Geometry reduced = reducer.reduce(geometry);
                try {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(
                                Level.FINE,
                                "Failed to intersect the geometry with the projection area of "
                                        + "validity mask, trying a precision reduction approach with a precision of "
                                        + precision);
                    }
                    result = reduced.intersection(mask);
                    break;
                } catch (Exception e3) {
                    precision *= 10;
                }
            }

            if (result == null) {
                LOGGER.log(Level.WARNING,
                        "Failed to intersect the geometry with the projection area of "
                                + "validity mask, returning the original geometry: " + geometry);
                result = geometry;
            }
        }
        
        // workaround for a JTS bug, sometimes it returns empty results
        // even if the two geometries are indeed intersecting
        if (result.isEmpty() && geometry.intersects(mask)) {
            try {
                result = EnhancedPrecisionOp.intersection(geometry, mask);
            } catch (Exception e2) {
                result = geometry;
            }
        }

        // handle in special way empty intersections
        if (result.isEmpty()) {
            return null;
        } else {
            return result;
        }
    }
    
    /**
     * Can modify/wrap the transform to handle specific projection issues
     * @return
     * @throws FactoryException 
     */
    public MathTransform getRenderingTransform(MathTransform mt) throws FactoryException {
        List<MathTransform> elements = new ArrayList<MathTransform>();
        accumulateTransforms(mt, elements);
        
        List<MathTransform> wrapped = new ArrayList<MathTransform>();
        List<MathTransform> datumShiftChain = null;
        boolean datumShiftDetected = false;
        for (MathTransform element : elements) {
            if(datumShiftChain != null) {
                datumShiftChain.add(element);
                if(element.getClass().getName().equals(GeocentricTransform.class.getName() + "$Inverse")) {
                    datumShiftDetected = true;
                    MathTransform combined = concatenateTransforms(datumShiftChain);
                    GeographicOffsetWrapper wrapper = new GeographicOffsetWrapper(combined);
                    wrapped.add(wrapper);
                    datumShiftChain = null;
                } 
            } else if(element instanceof GeocentricTransform) {
                datumShiftChain = new ArrayList<MathTransform>();
                datumShiftChain.add(element);
            } else {
                wrapped.add(element);
            }
        }
        
        if(datumShiftDetected) {
            if(datumShiftChain != null) {
                wrapped.addAll(datumShiftChain);
            }
            return concatenateTransforms(wrapped);
        } else {
            return mt;
        }
    }

    private MathTransform concatenateTransforms(List<MathTransform> datumShiftChain) {
		if(datumShiftChain.size() == 1) {
			return datumShiftChain.get(0);
		} else {
			MathTransform mt = ConcatenatedTransform.create(datumShiftChain.get(0), datumShiftChain.get(1));
			for (int i = 2; i < datumShiftChain.size(); i++) {
				MathTransform curr = datumShiftChain.get(i);
				mt = ConcatenatedTransform.create(mt, curr);
			}
			
			return mt;
		}
	}

	private void accumulateTransforms(MathTransform mt, List<MathTransform> elements) {
		if(mt instanceof ConcatenatedTransform) {
			ConcatenatedTransform ct = (ConcatenatedTransform) mt;
			accumulateTransforms(ct.transform1, elements);
			accumulateTransforms(ct.transform2, elements);
		} else {
			elements.add(mt);
		}
		
	}

	/**
     * Processes the geometry already projected to the target SRS. May return null if the geometry
     * is not to be drawn.
     * @param mt optional reverse transformation to facilitate unwrapping
     */
    public Geometry postProcess(MathTransform mt, Geometry geometry) {
        return geometry;
    }

    /**
     * Returns the area where the transformation from source to target is valid, expressed in the
     * source coordinate reference system, or null if there is no limit
     * 
     * @return
     */
    public ReferencedEnvelope getValidAreaBounds() {
        return validAreaBounds;
    }

    void setCentralMeridian(double centralMeridian) {
        // compute the earth radius
        try {
            CoordinateReferenceSystem targetCRS = renderingEnvelope.getCoordinateReferenceSystem();
            MathTransform mt = CRS.findMathTransform(WGS84, targetCRS, true);
            double[] src = new double[] { centralMeridian, 0, 180 + centralMeridian, 0 };
            double[] dst = new double[4];
            mt.transform(src, 0, dst, 0, 2);

            if (CRS.getAxisOrder(targetCRS) == CRS.AxisOrder.NORTH_EAST) {
                radius = Math.abs(dst[3] - dst[1]);
            } else {
                radius = Math.abs(dst[2] - dst[0]);
            }

            if (radius <= 0) {
                throw new RuntimeException("Computed Earth radius is 0, what is going on?");
            }
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error computing the Earth radius "
                    + "in the current projection", e);
        }

        // compute the x of the dateline in the rendering CRS
        try {
            double[] ordinates = new double[] { 180, -80, 180, 80 };
            MathTransform mt = CRS.findMathTransform(DefaultGeographicCRS.WGS84,
                    renderingEnvelope.getCoordinateReferenceSystem());
            mt.transform(ordinates, 0, ordinates, 0, 2);
            datelineX = ordinates[0];
        } catch (Exception e) {
            // should never happen...
            throw new RuntimeException(e);
        }

    }

    /**
     * Private method for adding to the input List only the {@link Geometry} objects of the input {@link GeometryCollection} which belongs to the
     * defined geometryType
     * 
     * @param geoms
     * @param geometryType
     */
    private void addGeometries(List<Geometry> geoms, GeometryCollection collection,
            String geometryType) {
        // Check if the list exists
        if (geoms == null) {
            return;
        }
        // Check the Geometry type
        if (geometryType == null || geometryType.isEmpty()) {
            return;
        }
        // Check the collection
        if (collection == null || collection.getNumGeometries() <= 0) {
            return;
        }
        // Get the number of Geometries
        int numGeometries = collection.getNumGeometries();
        // Cycle on the Geometries
        for (int i = 0; i < numGeometries; i++) {
            // get the Geometry
            Geometry geo = collection.getGeometryN(i);
            // If it belongs to the correct Geometry type, it is added to the Liats
            if (geo.getGeometryType().equals(geometryType)) {
                geoms.add(geo);
                // Otherwise if it is a collection we try to iterate on it (recursion)
            } else if (geo instanceof GeometryCollection) {
                addGeometries(geoms, (GeometryCollection) geo, geometryType);
            }
        }
    }

}
